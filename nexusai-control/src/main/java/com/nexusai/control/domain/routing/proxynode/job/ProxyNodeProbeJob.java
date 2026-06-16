package com.nexusai.control.domain.routing.proxynode.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.control.domain.platform.notify.service.NotificationService;
import com.nexusai.control.domain.platform.ops.JobHeartbeats;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.control.domain.routing.proxynode.service.ProxyNodeService;
import com.nexusai.control.domain.routing.proxynode.vo.ProxyTestResultVo;
import com.nexusai.persistence.mapper.NaiProxyNodeMapper;
import com.nexusai.persistence.mapper.NaiProxyNodeMetricMapper;
import com.nexusai.persistence.po.NaiProxyNode;
import com.nexusai.persistence.po.NaiProxyNodeMetric;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 代理节点周期探测（进程内守护线程 + Redisson leader 锁·对齐 NodeHeartbeat/TaskPoller 纪律·不引入重型调度器）：
 * 每 60s 探测全部节点 → 延迟 EMA + 状态翻转（写事件）+ 1h 指标桶 upsert。
 * 系统设置「定时任务」可开关（enable_proxy_node_probe·默认开）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyNodeProbeJob {

    private static final long INTERVAL_MS = 60_000L;
    private static final String LEADER_LOCK = "nai:lock:proxy-probe";
    /** 延迟 EMA 平滑系数 */
    private static final double EMA_ALPHA = 0.3;
    private static final int MILLIS_PER_SECOND = 1000;
    /** leader 锁租约相对探测周期的提前释放余量（秒·防锁过界压下一轮） */
    private static final long LOCK_LEASE_MARGIN_SECONDS = 5L;
    private static final String STATUS_ONLINE = "online";
    private static final String STATUS_OFFLINE = "offline";
    private static final String DEFAULT_PROBE_ERROR = "探测失败";

    private final NaiProxyNodeMapper nodeMapper;
    private final NaiProxyNodeMetricMapper metricMapper;
    private final ProxyNodeService proxyNodeService;
    private final SystemConfigService systemConfig;
    private final NotificationService notificationService;
    private final RedissonClient redisson;

    private volatile boolean running = true;
    private Thread daemon;

    @PostConstruct
    public void start() {
        daemon = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    runOnce();
                    JobHeartbeats.beat("proxy-node-probe");
                } catch (Exception e) {
                    log.warn("代理节点探测轮异常: {}", e.toString());
                }
            }
        }, "nai-proxy-probe");
        daemon.setDaemon(true);
        daemon.start();
    }

    void runOnce() {
        if (!systemConfig.getBool("enable_proxy_node_probe", true)) {
            return;
        }
        RLock lock = redisson.getLock(LEADER_LOCK);
        boolean acquired;
        try {
            acquired = lock.tryLock(0,
                    INTERVAL_MS / MILLIS_PER_SECOND - LOCK_LEASE_MARGIN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            return;
        }
        List<NaiProxyNode> nodes = nodeMapper.selectList(null);
        for (NaiProxyNode node : nodes) {
            probeOne(node);
        }
    }

    private void probeOne(NaiProxyNode node) {
        ProxyTestResultVo result = proxyNodeService.probe(
                node.getProxyUrl(), node.getUsername(), proxyNodeService.plainPassword(node));
        boolean ok = result.success();
        long latency = result.latencyMs();
        String error = result.error() == null ? DEFAULT_PROBE_ERROR : result.error();
        LocalDateTime now = LocalDateTime.now();

        String prev = node.getStatus();
        node.setStatus(ok ? STATUS_ONLINE : STATUS_OFFLINE);
        node.setLastProbeAt(now);
        if (ok) {
            node.setLastOkAt(now);
            double ema = node.getAvgLatencyMs() == null
                    ? latency
                    : EMA_ALPHA * latency + (1 - EMA_ALPHA) * node.getAvgLatencyMs();
            node.setAvgLatencyMs(ema);
        }
        nodeMapper.updateById(node);

        if (!node.getStatus().equals(prev)) {
            proxyNodeService.recordEvent(node.getId(),
                    ok ? "connected" : "disconnected",
                    ok ? ("探测恢复，延迟 " + latency + "ms") : error);
            // 通知服务事件源：状态翻转推送（首次探测 prev=null 不打扰）
            if (prev != null) {
                notificationService.notify("proxy_node_status",
                        "代理节点" + (ok ? "恢复" : "离线") + ": " + node.getName(),
                        node.getProxyUrl() + (ok ? "（延迟 " + latency + "ms）" : "（" + error + "）"));
            }
        } else if (!ok) {
            proxyNodeService.recordEvent(node.getId(), "error", error);
        }

        upsertBucket(node.getId(), now.truncatedTo(ChronoUnit.HOURS), ok, latency);
    }

    private void upsertBucket(long nodeId, LocalDateTime bucket, boolean ok, long latency) {
        NaiProxyNodeMetric row = metricMapper.selectOne(
                new LambdaQueryWrapper<NaiProxyNodeMetric>()
                        .eq(NaiProxyNodeMetric::getNodeId, nodeId)
                        .eq(NaiProxyNodeMetric::getBucketStart, bucket));
        if (row == null) {
            row = new NaiProxyNodeMetric();
            row.setNodeId(nodeId);
            row.setBucketStart(bucket);
            row.setSamples(1);
            row.setOkSamples(ok ? 1 : 0);
            row.setLatencySumMs(ok ? latency : 0);
            row.setLatencyMaxMs(ok ? (int) latency : 0);
            row.setErrors(ok ? 0 : 1);
            metricMapper.insert(row);
        } else {
            row.setSamples(row.getSamples() + 1);
            row.setOkSamples(row.getOkSamples() + (ok ? 1 : 0));
            row.setLatencySumMs(row.getLatencySumMs() + (ok ? latency : 0));
            row.setLatencyMaxMs(Math.max(row.getLatencyMaxMs(), ok ? (int) latency : 0));
            row.setErrors(row.getErrors() + (ok ? 0 : 1));
            metricMapper.update(row, new LambdaQueryWrapper<NaiProxyNodeMetric>()
                    .eq(NaiProxyNodeMetric::getNodeId, nodeId)
                    .eq(NaiProxyNodeMetric::getBucketStart, bucket));
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
