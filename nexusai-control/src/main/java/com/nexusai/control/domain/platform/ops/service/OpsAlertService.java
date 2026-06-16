package com.nexusai.control.domain.platform.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexusai.control.domain.platform.ops.vo.AlertEventPageVo;
import com.nexusai.control.domain.platform.ops.vo.AlertRuleTestResultVo;
import com.nexusai.control.domain.platform.runtime.service.RuntimeViewService;
import com.nexusai.observe.metrics.RelayInflightMetrics;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.RangeTotals;
import com.nexusai.persistence.mapper.NaiAlertEventMapper;
import com.nexusai.persistence.mapper.NaiAlertRuleMapper;
import com.nexusai.persistence.mapper.NaiFailoverEventMapper;
import com.nexusai.persistence.mapper.NaiNodeMapper;
import com.nexusai.persistence.po.NaiAlertEvent;
import com.nexusai.persistence.po.NaiAlertRule;
import com.nexusai.persistence.po.NaiFailoverEvent;
import com.nexusai.persistence.po.NaiNode;
import com.sun.management.OperatingSystemMXBean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nexusai.persistence.log.stat.PerfFilter;

/**
 * 运维告警服务（告警规则/告警事件）：
 * 规则 CRUD + 事件查询/状态流转 + 指标计算（评估器与规则测试共用同一口径）。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class OpsAlertService {

    /** 支持的指标类型白名单（前端下拉与校验共用） */
    public static final Set<String> METRIC_TYPES = Set.of(
            "error_rate", "success_rate", "qps", "request_count", "error_count",
            "p99_latency_ms", "ttft_p99_ms", "inflight_concurrency",
            "schedulable_key_ratio", "circuit_open_count", "node_offline_count",
            "failover_count", "cpu_usage_percent", "memory_usage_percent");

    private static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=", "==", "!=");
    private static final Set<String> SEVERITIES = Set.of("P0", "P1", "P2", "P3");

    /** 慢请求阈值（ms·channelPerf 聚合的 slow_request 判定线·与运维仪表盘同口径） */
    private static final int SLOW_REQUEST_THRESHOLD_MS = 30_000;

    private final NaiAlertRuleMapper ruleMapper;
    private final NaiAlertEventMapper eventMapper;
    private final RequestLogStore logStore;
    private final RelayInflightMetrics inflightMetrics;
    private final RuntimeViewService runtimeViewService;
    private final NaiNodeMapper nodeMapper;
    private final NaiFailoverEventMapper failoverMapper;

    // ---------- 规则 CRUD ----------

    public List<NaiAlertRule> rules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<NaiAlertRule>()
                .orderByDesc(NaiAlertRule::getId));
    }

    public NaiAlertRule saveRule(Long id, Map<String, Object> body) {
        NaiAlertRule rule = id == null ? new NaiAlertRule() : ruleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("告警规则不存在: " + id);
        }
        String name = str(body.get("name"));
        String metricType = str(body.get("metricType"));
        String operator = str(body.get("operator"));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        if (metricType == null || !METRIC_TYPES.contains(metricType)) {
            throw new IllegalArgumentException("不支持的指标类型: " + metricType);
        }
        if (operator == null || !OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("不支持的比较符: " + operator);
        }
        if (!(body.get("threshold") instanceof Number threshold)) {
            throw new IllegalArgumentException("阈值必须为数值");
        }
        String severity = body.get("severity") == null ? "P2" : str(body.get("severity"));
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalArgumentException("不支持的级别: " + severity);
        }
        rule.setName(name.trim());
        rule.setDescription(str(body.get("description")));
        rule.setEnabledFlag(Boolean.FALSE.equals(body.get("enabled")) ? 0 : 1);
        rule.setMetricType(metricType);
        rule.setOperator(operator);
        rule.setThreshold(threshold.doubleValue());
        rule.setWindowMinutes(clamp(intOf(body.get("windowMinutes"), 5), 1, 24 * 60));
        rule.setSustainedMinutes(clamp(intOf(body.get("sustainedMinutes"), 1), 1, 24 * 60));
        rule.setCooldownMinutes(clamp(intOf(body.get("cooldownMinutes"), 10), 0, 24 * 60));
        rule.setSeverity(severity);
        rule.setNotifyFlag(Boolean.FALSE.equals(body.get("notify")) ? 0 : 1);
        if (rule.getId() == null) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
        return rule;
    }

    public void deleteRule(long id) {
        ruleMapper.deleteById(id);
    }

    /** 规则测试（dry-run）：按规则口径现算指标，返回值与是否违反。 */
    public AlertRuleTestResultVo testRule(Map<String, Object> body) {
        String metricType = str(body.get("metricType"));
        if (metricType == null || !METRIC_TYPES.contains(metricType)) {
            throw new IllegalArgumentException("不支持的指标类型: " + metricType);
        }
        String operator = str(body.get("operator"));
        double threshold = body.get("threshold") instanceof Number n ? n.doubleValue() : 0;
        int windowMinutes = clamp(intOf(body.get("windowMinutes"), 5), 1, 24 * 60);
        Double value = computeMetric(metricType, windowMinutes);
        boolean breached = value != null && operator != null && compare(value, operator, threshold);
        return new AlertRuleTestResultVo(metricType, windowMinutes, value, breached);
    }

    // ---------- 事件 ----------

    public AlertEventPageVo events(String status, String severity, Integer hours, int page, int pageSize) {
        LambdaQueryWrapper<NaiAlertEvent> w = new LambdaQueryWrapper<NaiAlertEvent>()
                .orderByDesc(NaiAlertEvent::getId);
        if (status != null && !status.isBlank()) {
            w.eq(NaiAlertEvent::getStatus, status);
        }
        if (severity != null && !severity.isBlank()) {
            w.eq(NaiAlertEvent::getSeverity, severity);
        }
        if (hours != null && hours > 0) {
            w.ge(NaiAlertEvent::getFiredDt, LocalDateTime.now().minusHours(Math.min(hours, 24 * 30)));
        }
        Page<NaiAlertEvent> p = eventMapper.selectPage(new Page<>(page, Math.min(pageSize, 200)), w);
        return new AlertEventPageVo(p.getRecords(), p.getTotal());
    }

    /** 事件状态流转（手动解除）。 */
    public void updateEventStatus(long id, String status) {
        if (!Set.of("firing", "resolved", "manual_resolved").contains(status)) {
            throw new IllegalArgumentException("不支持的状态: " + status);
        }
        NaiAlertEvent event = eventMapper.selectById(id);
        if (event == null) {
            throw new IllegalArgumentException("告警事件不存在: " + id);
        }
        event.setStatus(status);
        if (!"firing".equals(status) && event.getResolvedDt() == null) {
            event.setResolvedDt(LocalDateTime.now());
        }
        eventMapper.updateById(event);
    }

    // ---------- 指标计算（评估器共用口径） ----------

    /** 计算指标当前值（窗口聚合或瞬时值·无样本返回 null 表示不评估）。 */
    public Double computeMetric(String metricType, int windowMinutes) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusMinutes(windowMinutes);
        switch (metricType) {
            case "error_rate", "success_rate", "qps", "request_count", "error_count" -> {
                RangeTotals t = logStore.totalsBetween(since, now);
                long total = t.total();
                long errors = total - t.success();
                return switch (metricType) {
                    case "request_count" -> (double) total;
                    case "error_count" -> (double) errors;
                    case "qps" -> round2((double) total / (windowMinutes * 60L));
                    case "error_rate" -> total == 0 ? null : round2((double) errors / total * 100);
                    default -> total == 0 ? null : round2((double) t.success() / total * 100);
                };
            }
            case "p99_latency_ms", "ttft_p99_ms" -> {
                var overall = logStore.channelPerf(PerfFilter.NONE,
                        since, now, SLOW_REQUEST_THRESHOLD_MS, false);
                if (overall.isEmpty()) {
                    return null;
                }
                Long v = "p99_latency_ms".equals(metricType)
                        ? overall.get(0).p99DurationMs() : overall.get(0).p99TtftMs();
                return v == null ? null : v.doubleValue();
            }
            case "inflight_concurrency" -> {
                return (double) inflightMetrics.inFlight();
            }
            case "schedulable_key_ratio" -> {
                List<Map<String, Object>> keys = runtimeViewService.keySnapshots(null);
                if (keys.isEmpty()) {
                    return null;
                }
                long ok = keys.stream().filter(k -> Boolean.TRUE.equals(k.get("schedulable"))).count();
                return round2((double) ok / keys.size() * 100);
            }
            case "circuit_open_count" -> {
                long channels = runtimeViewService.channelSnapshots().stream()
                        .filter(c -> Boolean.FALSE.equals(c.get("schedulable"))).count();
                long keys = runtimeViewService.keySnapshots(null).stream()
                        .filter(k -> Boolean.FALSE.equals(k.get("schedulable"))).count();
                return (double) (channels + keys);
            }
            case "node_offline_count" -> {
                List<NaiNode> nodes = nodeMapper.selectList(null);
                return (double) nodes.stream().filter(n -> n.getUpdateDt() == null
                        || Duration.between(n.getUpdateDt(), now).getSeconds() > 90).count();
            }
            case "failover_count" -> {
                return (double) failoverMapper.selectCount(new LambdaQueryWrapper<NaiFailoverEvent>()
                        .ge(NaiFailoverEvent::getCreateDt, since));
            }
            case "cpu_usage_percent" -> {
                var os = ManagementFactory.getOperatingSystemMXBean();
                double cpu = os instanceof OperatingSystemMXBean sun ? sun.getCpuLoad() : -1;
                return cpu >= 0 ? round2(cpu * 100) : null;
            }
            case "memory_usage_percent" -> {
                Runtime rt = Runtime.getRuntime();
                return round2((double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory() * 100);
            }
            default -> {
                return null;
            }
        }
    }

    /** 阈值比较（== / != 容浮点误差）。 */
    public static boolean compare(double value, String operator, double threshold) {
        return switch (operator) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            case "==" -> Math.abs(value - threshold) < 1e-9;
            case "!=" -> Math.abs(value - threshold) >= 1e-9;
            default -> false;
        };
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
