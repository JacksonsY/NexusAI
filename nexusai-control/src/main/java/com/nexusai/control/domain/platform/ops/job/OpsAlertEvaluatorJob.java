package com.nexusai.control.domain.platform.ops.job;

import com.nexusai.control.domain.platform.ops.JobHeartbeats;
import com.nexusai.control.domain.platform.ops.service.OpsAlertService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.control.domain.platform.notify.service.NotificationService;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.persistence.mapper.NaiAlertEventMapper;
import com.nexusai.persistence.mapper.NaiAlertRuleMapper;
import com.nexusai.persistence.po.NaiAlertEvent;
import com.nexusai.persistence.po.NaiAlertRule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 告警规则评估器（进程内守护线程 + Redisson leader 锁，
 * 对齐 ProxyNodeProbeJob 纪律·不引入重型调度器）：每 60s 评估全部启用规则——
 * 连续违反达 sustained 分钟 → 冷却期外创建 firing 事件 + 走通知服务（通知项 ops_alert）；
 * 指标恢复 → firing 事件自动转 resolved。
 * <p>sustained 用内存连续违反计数（评估间隔=1 分钟 → 次数≈分钟数）；leader 易主后计数重新累计，
 * 代价是触发最多延迟一个 sustained 周期，换取无共享状态。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsAlertEvaluatorJob {

    /** 评估间隔 KV 键（设置弹窗可调） */
    public static final String CFG_EVAL_INTERVAL = "ops.alert_eval_interval_seconds";
    /** 评估开关 KV 键（与 ModuleService 特性开关同名空间） */
    public static final String CFG_EVAL_ENABLED = "enable_ops_alert_eval";

    private static final String LEADER_LOCK = "nai:lock:ops-alert-eval";
    /** 心跳/线程名（运维监控页守护任务健康判定用） */
    private static final String JOB_NAME = "ops-alert-eval";

    /** 评估间隔钳制（秒·默认 60s） */
    private static final int EVAL_INTERVAL_MIN_SECONDS = 10;
    private static final int EVAL_INTERVAL_MAX_SECONDS = 3600;
    private static final int EVAL_INTERVAL_DEFAULT_SECONDS = 60;
    /** leader 锁租约相对评估间隔留的余量（秒）与下限（秒） */
    private static final int LOCK_LEASE_MARGIN_SECONDS = 5;
    private static final int LOCK_LEASE_MIN_SECONDS = 5;
    private static final long MILLIS_PER_SECOND = 1000L;

    /** 告警事件状态与通知项键 */
    private static final String STATUS_FIRING = "firing";
    private static final String STATUS_RESOLVED = "resolved";
    private static final String NOTIFY_ITEM_OPS_ALERT = "ops_alert";

    private final NaiAlertRuleMapper ruleMapper;
    private final NaiAlertEventMapper eventMapper;
    private final OpsAlertService alertService;
    private final NotificationService notificationService;
    private final SystemConfigService systemConfig;
    private final RedissonClient redisson;

    /** ruleId → 连续违反次数（leader 本地态） */
    private final Map<Long, Integer> breaches = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private Thread daemon;

    @PostConstruct
    public void start() {
        daemon = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(intervalSeconds() * MILLIS_PER_SECOND);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    runOnce();
                    JobHeartbeats.beat(JOB_NAME);
                } catch (Exception e) {
                    log.warn("告警评估轮异常: {}", e.toString());
                }
            }
        }, "nai-" + JOB_NAME);
        daemon.setDaemon(true);
        daemon.start();
    }

    /** 评估间隔秒（KV 可调·钳 10s~1h·默认 60s）。 */
    int intervalSeconds() {
        return Math.max(EVAL_INTERVAL_MIN_SECONDS,
                Math.min(systemConfig.getInt(CFG_EVAL_INTERVAL, EVAL_INTERVAL_DEFAULT_SECONDS), EVAL_INTERVAL_MAX_SECONDS));
    }

    void runOnce() throws InterruptedException {
        if (!systemConfig.getBool(CFG_EVAL_ENABLED, true)) {
            return;
        }
        RLock lock = redisson.getLock(LEADER_LOCK);
        long leaseSeconds = Math.max(intervalSeconds() - LOCK_LEASE_MARGIN_SECONDS, LOCK_LEASE_MIN_SECONDS);
        if (!lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS)) {
            return;
        }
        List<NaiAlertRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<NaiAlertRule>()
                .eq(NaiAlertRule::getEnabledFlag, 1));
        for (NaiAlertRule rule : rules) {
            try {
                evaluate(rule);
            } catch (Exception e) {
                log.warn("规则评估异常 [{}]: {}", rule.getName(), e.toString());
            }
        }
        // 清理已删/已停规则的残留计数
        breaches.keySet().retainAll(rules.stream().map(NaiAlertRule::getId).collect(Collectors.toSet()));
    }

    private void evaluate(NaiAlertRule rule) {
        Double value = alertService.computeMetric(rule.getMetricType(), rule.getWindowMinutes());
        if (value == null) {
            return;
        }
        boolean breached = OpsAlertService.compare(value, rule.getOperator(), rule.getThreshold());
        if (!breached) {
            breaches.remove(rule.getId());
            resolveFiring(rule, value);
            return;
        }
        int count = breaches.merge(rule.getId(), 1, Integer::sum);
        int needed = Math.max(1, rule.getSustainedMinutes());
        if (count < needed) {
            return;
        }
        // 冷却期内不重复触发
        LocalDateTime now = LocalDateTime.now();
        if (rule.getCooldownMinutes() != null && rule.getCooldownMinutes() > 0
                && rule.getLastTriggeredDt() != null
                && Duration.between(rule.getLastTriggeredDt(), now).toMinutes() < rule.getCooldownMinutes()) {
            return;
        }
        // 已有 firing 事件不重复开（持续违反只算一次事件）
        if (activeFiring(rule.getId()) != null) {
            return;
        }
        NaiAlertEvent event = new NaiAlertEvent();
        event.setRuleId(rule.getId());
        event.setRuleName(rule.getName());
        event.setSeverity(rule.getSeverity());
        event.setStatus(STATUS_FIRING);
        event.setTitle("[" + rule.getSeverity() + "] " + rule.getName());
        event.setDescription(rule.getMetricType() + " = " + value + " " + rule.getOperator()
                + " " + rule.getThreshold() + "（窗口 " + rule.getWindowMinutes() + " 分钟·持续 "
                + rule.getSustainedMinutes() + " 分钟）");
        event.setMetricValue(value);
        event.setThresholdValue(rule.getThreshold());
        event.setFiredDt(now);
        event.setNotifiedFlag(0);
        eventMapper.insert(event);

        rule.setLastTriggeredDt(now);
        ruleMapper.updateById(rule);

        if (rule.getNotifyFlag() != null && rule.getNotifyFlag() == 1) {
            notificationService.notify(NOTIFY_ITEM_OPS_ALERT, event.getTitle(), event.getDescription());
            event.setNotifiedFlag(1);
            eventMapper.updateById(event);
        }
        log.info("告警触发 [{}] value={} threshold={}", rule.getName(), value, rule.getThreshold());
    }

    /** 指标恢复 → firing 事件自动转 resolved（可选恢复通知：仅记录不打扰）。 */
    private void resolveFiring(NaiAlertRule rule, double value) {
        NaiAlertEvent firing = activeFiring(rule.getId());
        if (firing == null) {
            return;
        }
        firing.setStatus(STATUS_RESOLVED);
        firing.setResolvedDt(LocalDateTime.now());
        eventMapper.updateById(firing);
        log.info("告警恢复 [{}] value={}", rule.getName(), value);
    }

    private NaiAlertEvent activeFiring(long ruleId) {
        return eventMapper.selectOne(new LambdaQueryWrapper<NaiAlertEvent>()
                .eq(NaiAlertEvent::getRuleId, ruleId).eq(NaiAlertEvent::getStatus, STATUS_FIRING)
                .orderByDesc(NaiAlertEvent::getId).last("limit 1"));
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (daemon != null) {
            daemon.interrupt();
        }
    }
}
