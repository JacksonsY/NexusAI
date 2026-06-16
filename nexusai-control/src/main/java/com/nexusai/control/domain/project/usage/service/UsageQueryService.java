package com.nexusai.control.domain.project.usage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.PageQuery;
import com.nexusai.common.model.PageResult;
import com.nexusai.control.domain.project.usage.vo.ActiveRequestVo;
import com.nexusai.control.domain.project.usage.vo.IntervalTimelineVo;
import com.nexusai.control.domain.project.usage.vo.UsageAggItemVo;
import com.nexusai.control.domain.project.usage.vo.UsageHeatmapVo;
import com.nexusai.observe.log.ActiveRequestRegistry;
import com.nexusai.persistence.log.RequestBodyBlob;
import com.nexusai.persistence.log.RequestBodyStore;
import com.nexusai.persistence.log.RequestLogPage;
import com.nexusai.persistence.log.RequestLogQuery;
import com.nexusai.persistence.log.RequestLogRecord;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.HeatmapDay;
import com.nexusai.persistence.log.stat.IntervalRow;
import com.nexusai.persistence.log.stat.UsageAggRow;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.mapper.NaiUsageLogMapper;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiProject;
import com.nexusai.persistence.po.NaiUsageLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 用量/请求日志查询域逻辑（控制器只留参数绑定与 R 包装·域内薄分层）：
 * 计量日志走关系域（nai_usage_log·计量事件 outbox）；请求日志经 {@link RequestLogStore} SPI（mysql/pg/clickhouse 三选一）。
 * 用量视图：列表多维筛选 + 维度聚合 + 365 天热图 + 请求间隔时间线 + 活跃请求 + 正文/cURL 重放。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageQueryService {

    /** 鉴权类 header 脱敏占位（不回吐真实密钥）。 */
    private static final String UPSTREAM_API_KEY_PLACEHOLDER = "Bearer $UPSTREAM_API_KEY";
    /** 脱敏后的密钥值占位（headerOverride 命中敏感词时替换）。 */
    private static final String UPSTREAM_API_KEY_VALUE = "$UPSTREAM_API_KEY";
    /** 间隔时间线原始拉取上限（差分前的最大日志行数）。 */
    private static final int INTERVAL_FETCH_LIMIT = 20000;
    /** 热图固定窗口天数（含今天·共 365 天）。 */
    private static final int HEATMAP_DAYS = 365;

    private final NaiUsageLogMapper usageLogMapper;
    private final RequestLogStore requestLogStore;
    private final RequestBodyStore requestBodyStore;
    private final NaiChannelMapper channelMapper;
    private final NaiProjectMapper projectMapper;
    private final ActiveRequestRegistry activeRegistry;
    private final ObjectMapper objectMapper;

    /** 计量日志分页（nai_usage_log·计量事件 outbox 同源）。 */
    public PageResult<NaiUsageLog> usageLogs(PageQuery pq, String projectCode, String model) {
        Page<NaiUsageLog> page = usageLogMapper.selectPage(
                Page.of(pq.getPageNum(), pq.getPageSize()),
                new LambdaQueryWrapper<NaiUsageLog>()
                        .eq(projectCode != null && !projectCode.isBlank(), NaiUsageLog::getProjectCode, projectCode)
                        .eq(model != null && !model.isBlank(), NaiUsageLog::getModel, model)
                        .orderByDesc(NaiUsageLog::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), pq.getPageNum(), pq.getPageSize());
    }

    /** 请求日志分页（经 SPI·存储无关）。 */
    public RequestLogPage requests(RequestLogQuery query) {
        return requestLogStore.page(query);
    }

    /**
     * 维度聚合（用量分析面板）：物理列由调用方经白名单解析；
     * 派生值（成功率/错误数/缓存命中率）Java 端计算；渠道/项目维度批量映射名称（不 JOIN·内存映射）。
     */
    public List<UsageAggItemVo> aggregate(RequestLogQuery query, String groupBy, String column, int limit) {
        List<UsageAggRow> rows = requestLogStore.aggregateBy(query, column, limit);
        Map<String, String> channelNames = "channel".equals(groupBy) ? channelNames(rows) : Map.of();
        Map<String, String> projectNames = "project".equals(groupBy)
                ? projectNames(rows.stream().map(UsageAggRow::key).toList()) : Map.of();
        List<UsageAggItemVo> out = new ArrayList<>(rows.size());
        for (UsageAggRow row : rows) {
            String label = switch (groupBy) {
                case "channel" -> channelNames.getOrDefault(row.key(), "渠道#" + row.key());
                case "project" -> projectNames.getOrDefault(row.key(), row.key());
                default -> row.key();
            };
            double successRate = row.requests() == 0 ? 100.0
                    : Math.round(row.success() * 10000.0 / row.requests()) / 100.0;
            long denom = row.promptTokens() + row.cacheTokens();
            double cacheHitRate = denom == 0 ? 0.0
                    : Math.round(row.cacheTokens() * 10000.0 / denom) / 100.0;
            out.add(new UsageAggItemVo(
                    row.key(), label, row.requests(), row.success(), row.requests() - row.success(),
                    successRate, row.promptTokens(), row.completionTokens(), row.cacheTokens(),
                    row.totalTokens(), row.costUsd(), row.avgDurationMs(), cacheHitRate));
        }
        return out;
    }

    /** 活跃度热图（固定 365 天·零请求日补齐）。 */
    public UsageHeatmapVo heatmap() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(HEATMAP_DAYS - 1L);
        Map<String, HeatmapDay> byDate = new LinkedHashMap<>();
        for (HeatmapDay d : requestLogStore.heatmapDaily(start.atStartOfDay())) {
            byDate.put(d.date(), d);
        }
        List<UsageHeatmapVo.Day> days = new ArrayList<>(HEATMAP_DAYS);
        long maxRequests = 0;
        long activeDays = 0;
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        for (LocalDate cur = start; !cur.isAfter(end); cur = cur.plusDays(1)) {
            HeatmapDay d = byDate.get(cur.format(fmt));
            long requests = d == null ? 0 : d.requests();
            maxRequests = Math.max(maxRequests, requests);
            if (requests > 0) {
                activeDays++;
            }
            days.add(new UsageHeatmapVo.Day(cur.format(fmt), requests,
                    d == null ? 0 : d.totalTokens(),
                    d == null ? BigDecimal.ZERO : d.costUsd()));
        }
        return new UsageHeatmapVo(start.format(fmt), end.format(fmt), days.size(), activeDays, maxRequests, days);
    }

    /**
     * 请求间隔时间线：近 N 小时内按项目分组的相邻请求间隔散点
     * （y=分钟·2 位小数）；总点数超限时按项目占比采样（每组保最近的点）。
     */
    public IntervalTimelineVo intervalTimeline(int hours, int limit) {
        List<IntervalRow> rows = requestLogStore.recentForIntervals(
                LocalDateTime.now().minusHours(hours), INTERVAL_FETCH_LIMIT);
        // rows 已按 (project_code, create_dt) 升序：逐项目差分出间隔点
        Map<String, List<IntervalTimelineVo.Point>> grouped = new LinkedHashMap<>();
        Set<String> models = new TreeSet<>();
        IntervalRow prev = null;
        for (IntervalRow row : rows) {
            if (prev != null && prev.projectCode() != null && prev.projectCode().equals(row.projectCode())) {
                double minutes = Duration.between(prev.createDt(), row.createDt()).toMillis() / 60000.0;
                if (row.model() != null) {
                    models.add(row.model());
                }
                IntervalTimelineVo.Point point = new IntervalTimelineVo.Point(
                        row.createDt().toString(), Math.round(minutes * 100.0) / 100.0,
                        row.model(), row.projectCode());
                grouped.computeIfAbsent(row.projectCode(), k -> new ArrayList<>()).add(point);
            }
            prev = row;
        }
        int total = grouped.values().stream().mapToInt(List::size).sum();
        List<IntervalTimelineVo.Point> points = new ArrayList<>();
        if (total > limit) {
            // 按组占比配额（每组至少 1 点·保最近）→ 时间升序合并
            for (List<IntervalTimelineVo.Point> pts : grouped.values()) {
                int quota = Math.max(1, (int) Math.floor((double) limit * pts.size() / total));
                points.addAll(pts.subList(Math.max(0, pts.size() - quota), pts.size()));
            }
        } else {
            grouped.values().forEach(points::addAll);
        }
        points.sort(Comparator.comparing(IntervalTimelineVo.Point::x));
        if (points.size() > limit) {
            points = points.subList(points.size() - limit, points.size());
        }
        Map<String, String> projects = projectNames(new ArrayList<>(grouped.keySet()));
        grouped.keySet().forEach(code -> projects.putIfAbsent(code, code));
        return new IntervalTimelineVo(hours, points.size(), points, projects, new ArrayList<>(models));
    }

    /** 进行中请求快照（进程内 {@link ActiveRequestRegistry}·完成后由日志行接棒）。 */
    public List<ActiveRequestVo> activeRequests() {
        long now = System.currentTimeMillis();
        List<ActiveRequestVo> out = new ArrayList<>();
        for (ActiveRequestRegistry.ActiveRequest a : activeRegistry.snapshot()) {
            String createDt = Instant.ofEpochMilli(a.getStartedAtMillis())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime().toString();
            out.add(new ActiveRequestVo(
                    a.getRequestId(), a.getPhase(), a.getProjectCode(), a.getChannelId(), a.getKeyId(),
                    a.getModel(), a.getTargetModel(), a.getModality(), a.getStreamMode(), a.getApiFormat(),
                    a.getEndpointApiFormat(), a.getClientIp(), a.getUserAgent(), a.getClientFamily(),
                    a.getHttpStatus(), a.getTtftMs(), now - a.getStartedAtMillis(), createDt));
        }
        return out;
    }

    /**
     * 项目作用域越权防护：受限令牌（callerProject 非空）只能读本项目的请求；跨项目/不存在一律按
     * 404「不存在」拒绝（不泄露他项目请求存在性，与 TaskRelayService.query 同口径）。platform/控制台 callerProject=null 放行。
     */
    private void assertRequestVisible(String requestId, String callerProject) {
        if (callerProject == null) {
            return;
        }
        RequestLogRecord rec = requestLogStore.findByRequestId(requestId);
        if (rec == null || !callerProject.equals(rec.getProjectCode())) {
            throw GatewayException.of(GatewayErrorCode.MODEL_NOT_FOUND, "请求不存在: " + requestId);
        }
    }

    /** 请求/响应正文（{@link RequestBodyStore} SPI·仅 store=rdb 且采集期间的请求有正文）。 */
    public Map<String, Object> requestBody(String requestId, String callerProject) {
        assertRequestVisible(requestId, callerProject);
        RequestBodyBlob blob = requestBodyStore.load(requestId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("collecting", requestBodyStore.isCollecting());
        data.put("requestBody", blob == null || blob.requestBody() == null
                ? null : new String(blob.requestBody(), StandardCharsets.UTF_8));
        data.put("responseBody", blob == null || blob.responseBody() == null
                ? null : new String(blob.responseBody(), StandardCharsets.UTF_8));
        data.put("createDt", blob == null ? null : blob.createDt());
        return data;
    }

    /**
     * cURL 重放命令（生成对<b>上游</b>的重放）：
     * 渠道 baseUrl + 模态端点 + headerOverride（鉴权值脱敏占位 {@code $UPSTREAM_API_KEY}）+ 捕获的请求体原文。
     * 请求不存在返回 null（由控制器转业务失败响应）。
     */
    public Map<String, Object> replayCurl(String requestId, String callerProject) {
        RequestLogRecord rec = requestLogStore.findByRequestId(requestId);
        if (rec == null) {
            return null;
        }
        // 越权防护：项目令牌只能重放本项目请求；跨项目按"不存在"处理（不泄露存在性·避免凭 requestId 枚举他项目上游配置）
        if (callerProject != null && !callerProject.equals(rec.getProjectCode())) {
            return null;
        }
        NaiChannel channel = rec.getChannelId() == null ? null : channelMapper.selectById(rec.getChannelId());
        String baseUrl = channel == null || channel.getBaseUrl() == null ? "$UPSTREAM_BASE_URL"
                : channel.getBaseUrl().replaceAll("/+$", "");
        String path = resolveEndpointPath(channel, rec.getModality());
        String url = baseUrl + path;
        // headers：鉴权占位脱敏（不回吐真实密钥）+ 渠道 headerOverride（值含敏感词时脱敏）
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", UPSTREAM_API_KEY_PLACEHOLDER);
        if (channel != null && channel.getHeaderOverride() != null && !channel.getHeaderOverride().isBlank()) {
            try {
                Map<String, String> ho = objectMapper.readValue(channel.getHeaderOverride(),
                        new TypeReference<Map<String, String>>() { });
                for (var en : ho.entrySet()) {
                    String lk = en.getKey().toLowerCase();
                    boolean sensitive = lk.contains("auth") || lk.contains("key") || lk.contains("token");
                    headers.put(en.getKey(), sensitive ? UPSTREAM_API_KEY_VALUE : en.getValue());
                }
            } catch (Exception e) {
                // headerOverride 非法 JSON 时跳过（渠道配置问题不阻塞 curl 生成）
                log.debug("headerOverride 解析失败 channelId={}: {}", channel.getId(), e.toString());
            }
        }
        RequestBodyBlob blob = requestBodyStore.load(requestId);
        String body = blob == null || blob.requestBody() == null
                ? null : new String(blob.requestBody(), StandardCharsets.UTF_8);
        StringBuilder curl = new StringBuilder("curl -X POST \\\n  '").append(url).append("'");
        for (var en : headers.entrySet()) {
            curl.append(" \\\n  -H '").append(en.getKey()).append(": ").append(en.getValue()).append("'");
        }
        if (body != null) {
            curl.append(" \\\n  -d '").append(body.replace("'", "'\\''")).append("'");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("curlCommand", curl.toString());
        data.put("url", url);
        data.put("headers", headers);
        data.put("body", body);
        data.put("bodyCaptured", body != null);
        return data;
    }

    /** 渠道 ID 串 → 渠道名（批量一次查询·不 JOIN）。 */
    private Map<String, String> channelNames(List<UsageAggRow> rows) {
        List<Long> ids = rows.stream().map(UsageAggRow::key)
                .filter(k -> k != null && !k.isEmpty() && k.chars().allMatch(Character::isDigit))
                .map(Long::valueOf).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (NaiChannel ch : channelMapper.selectBatchIds(ids)) {
            names.put(String.valueOf(ch.getId()), ch.getName());
        }
        return names;
    }

    /** 项目编码串 → 项目名（批量一次查询·不 JOIN）。 */
    private Map<String, String> projectNames(List<String> codes) {
        List<String> valid = codes.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();
        if (valid.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (NaiProject pj : projectMapper.selectList(
                Wrappers.lambdaQuery(NaiProject.class)
                        .in(NaiProject::getProjectCode, valid))) {
            names.put(pj.getProjectCode(), pj.getName());
        }
        return names;
    }

    /** 模态 → 上游端点路径：渠道 endpoints JSON 优先，否则按协议家族默认（OpenAI 系/Anthropic）。 */
    private String resolveEndpointPath(NaiChannel channel, String modality) {
        String m = modality == null ? "chat" : modality;
        if (channel != null && channel.getEndpoints() != null && !channel.getEndpoints().isBlank()) {
            try {
                Map<String, String> eps = objectMapper.readValue(channel.getEndpoints(),
                        new TypeReference<Map<String, String>>() { });
                String p = eps.get(m);
                if (p != null && !p.isBlank()) {
                    return p;
                }
            } catch (Exception e) {
                // endpoints 非法 JSON 时退默认映射
                log.debug("endpoints 解析失败 channelId={}: {}", channel.getId(), e.toString());
            }
        }
        boolean anthropic = channel != null && channel.getProtocol() != null
                && channel.getProtocol().toUpperCase().contains("ANTHROPIC");
        return switch (m) {
            case "embedding" -> "/v1/embeddings";
            case "image" -> "/v1/images/generations";
            case "rerank" -> "/v1/rerank";
            case "audio" -> "/v1/audio/speech";
            default -> anthropic ? "/v1/messages" : "/v1/chat/completions";
        };
    }
}
