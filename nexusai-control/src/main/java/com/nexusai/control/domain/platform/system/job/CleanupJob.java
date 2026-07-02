package com.nexusai.control.domain.platform.system.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.control.domain.governance.audit.service.AuditLogService;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.mapper.NaiFailoverEventMapper;
import com.nexusai.persistence.mapper.NaiNodeMapper;
import com.nexusai.persistence.mapper.NaiProxyNodeEventMapper;
import com.nexusai.persistence.mapper.NaiProxyNodeMetricMapper;
import com.nexusai.persistence.mapper.NaiTaskMapper;
import com.nexusai.persistence.po.NaiFailoverEvent;
import com.nexusai.persistence.po.NaiNode;
import com.nexusai.persistence.po.NaiProxyNodeEvent;
import com.nexusai.persistence.po.NaiProxyNodeMetric;
import com.nexusai.persistence.po.NaiTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

/**
 * 记录清理任务（进程内守护线程 + Redisson leader 锁·不引入重型调度器）：
 * 每日 cleanup_time（默认 03:30）按清理策略删过期请求日志/任务/故障转移事件/代理节点事件与指标；
 * 分批 cleanup_batch_size 防长事务。开关与保留期全部读系统设置（nai_system_config）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanupJob {

    private static final String LEADER_LOCK = "nai:lock:cleanup";

    /** 守护线程轮询间隔（ms·每分钟检查是否到点） */
    private static final long POLL_INTERVAL_MS = 60_000L;
    /** leader 锁租约（秒·一轮清理上限·分批防长事务后仍给足余量） */
    private static final long LOCK_LEASE_SECONDS = 600;
    /** 到点容忍窗口（分钟·守护线程分钟级抖动） */
    private static final long SCHEDULE_TOLERANCE_MINUTES = 10;

    private final SystemConfigService config;
    private final RequestLogStore requestLogStore;
    private final NaiTaskMapper taskMapper;
    private final NaiFailoverEventMapper failoverEventMapper;
    private final NaiProxyNodeEventMapper proxyEventMapper;
    private final NaiNodeMapper nodeMapper;
    private final NaiProxyNodeMetricMapper proxyMetricMapper;
    private final AuditLogService auditLogService;
    private final RedissonClient redisson;

    private volatile boolean running = true;
    private volatile LocalDate lastRunDate;
    private Thread daemon;

    @PostConstruct
    public void start() {
        daemon = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                    tick();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log.warn("清理任务轮异常: {}", e.toString());
                }
            }
        }, "nai-cleanup");
        daemon.setDaemon(true);
        daemon.start();
    }

    void tick() {
        if (!config.getBool("enable_auto_cleanup", true)) {
            return;
        }
        LocalTime scheduled = parseTime(config.getString("cleanup_time", "03:30"));
        LocalDateTime now = LocalDateTime.now();
        if (now.toLocalTime().isBefore(scheduled)
                || scheduled.plusMinutes(SCHEDULE_TOLERANCE_MINUTES).isBefore(now.toLocalTime())
                || now.toLocalDate().equals(lastRunDate)) {
            return;
        }
        RLock lock = redisson.getLock(LEADER_LOCK);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            lastRunDate = now.toLocalDate();
            return;
        }
        lastRunDate = now.toLocalDate();
        runCleanup();
    }

    /** 执行一轮清理（系统设置「记录清理策略」section 的真实执行体）。 */
    public void runCleanup() {
        int batch = Math.max(100, config.getInt("cleanup_batch_size", 1000));
        LocalDateTime now = LocalDateTime.now();

        // 请求日志（最大表·分批）
        LocalDateTime logCutoff = now.minusDays(config.getInt("log_retention_days", 30));
        int totalLogs = 0;
        int n;
        do {
            n = requestLogStore.deleteBefore(logCutoff, batch);
            if (n > 0) {
                totalLogs += n;
            }
        } while (n >= batch);

        LocalDateTime taskCutoff = now.minusDays(config.getInt("task_retention_days", 30));
        // 仅清理终态任务（SUCCESS/FAILED）；非终态（SUBMITTED/IN_PROGRESS·含已冻结未结算）永不物理删除，避免额度永久滞留、对账凭据销毁
        int tasks = taskMapper.delete(new LambdaQueryWrapper<NaiTask>()
                .lt(NaiTask::getCreateDt, taskCutoff)
                .in(NaiTask::getStatus, "SUCCESS", "FAILED"));

        LocalDateTime foCutoff = now.minusDays(config.getInt("failover_event_retention_days", 30));
        int failovers = failoverEventMapper.delete(
                new LambdaQueryWrapper<NaiFailoverEvent>().lt(NaiFailoverEvent::getCreateDt, foCutoff));

        LocalDateTime peCutoff = now.minusDays(config.getInt("proxy_node_event_retention_days", 30));
        int proxyEvents = proxyEventMapper.delete(
                new LambdaQueryWrapper<NaiProxyNodeEvent>().lt(NaiProxyNodeEvent::getCreateDt, peCutoff));

        LocalDateTime pmCutoff = now.minusDays(config.getInt("proxy_node_metric_retention_days", 30));
        int proxyMetrics = proxyMetricMapper.delete(
                new LambdaQueryWrapper<NaiProxyNodeMetric>().lt(NaiProxyNodeMetric::getBucketStart, pmCutoff));

        LocalDateTime auditCutoff = now.minusDays(config.getInt("audit_log_retention_days", 90));
        int audits = auditLogService.deleteBefore(auditCutoff);

        // 久离线集群节点剔除（默认 24h 无心跳物理删除）
        LocalDateTime nodeCutoff = now.minusHours(config.getInt("node_offline_purge_hours", 24));
        int nodes = nodeMapper.delete(new LambdaQueryWrapper<NaiNode>().lt(NaiNode::getUpdateDt, nodeCutoff));

        log.info("自动清理完成: requestLogs={} tasks={} failoverEvents={} proxyEvents={} proxyMetrics={} auditLogs={} offlineNodes={}",
                totalLogs, tasks, failovers, proxyEvents, proxyMetrics, audits, nodes);
    }

    private static LocalTime parseTime(String hhmm) {
        try {
            return LocalTime.parse(hhmm);
        } catch (Exception e) {
            return LocalTime.of(3, 30);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (daemon != null) {
            daemon.interrupt();
        }
    }
}
