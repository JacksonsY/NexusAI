package com.nexusai.control.domain.routing.node.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.control.domain.platform.ops.JobHeartbeats;
import com.nexusai.observe.log.ActiveRequestRegistry;
import com.nexusai.persistence.mapper.NaiNodeMapper;
import com.nexusai.persistence.po.NaiNode;
import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 网关实例心跳（多实例集群视图·进程内守护线程·非 @Scheduled）：
 * 启动即注册本节点并每 {@value #INTERVAL_MS}ms upsert {@code nai_node}（update_dt=末次心跳），
 * 心跳携带<b>运行时快照</b>（在途请求数/进程 CPU/JVM 堆）。
 * <p><b>leader 标记</b>（静态 master 角色的动态化替代）：Redis 键 {@code nai:cluster:leader}
 * trySet(nodeId, 3×心跳 TTL)，抢到/续期成功者 role=leader，挂了 TTL 到期自动易主——
 * 与 TaskPoller/探测/清理任务的 Redisson leader 锁同纪律，UI 据此展示当前 leader。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeHeartbeatJob {

    private static final long INTERVAL_MS = 15_000L;
    /** leader 标记 TTL = 3× 心跳周期（与离线判定阈值一致） */
    private static final long LEADER_TTL_SECONDS = 45L;
    private static final String LEADER_KEY = "nai:cluster:leader";
    private static final String ROLE_LEADER = "leader";
    private static final String ROLE_WORKER = "worker";
    /** 进程 CPU 占用率换算：load 分数 → 一位小数百分比（×1000 后 /10） */
    private static final double CPU_PERCENT_SCALE = 1000.0;
    private static final double CPU_PERCENT_DIVISOR = 10.0;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final String FALLBACK_HOST = "127.0.0.1";

    private final NaiNodeMapper nodeMapper;
    private final ActiveRequestRegistry activeRegistry;
    private final RedissonClient redisson;

    @Value("${server.port:8600}")
    private int port;

    @Value("${nexusai.node.labels:}")
    private String labels;

    private volatile boolean running = true;
    private Thread daemon;
    private String nodeId;

    @PostConstruct
    public void start() {
        String host = resolveHost();
        this.nodeId = host + ":" + port;
        long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        String version = String.valueOf(ManagementFactory.getRuntimeMXBean().getVmVersion());

        daemon = new Thread(() -> {
            while (running) {
                try {
                    beat(host, startedAt, version);
                    JobHeartbeats.beat("node-heartbeat");
                } catch (Exception e) {
                    log.debug("节点心跳失败（下次重试）: {}", e.getMessage());
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "nai-node-heartbeat");
        daemon.setDaemon(true);
        daemon.start();
        log.info("网关节点心跳启动 nodeId={}", nodeId);
    }

    private void beat(String host, long startedAt, String version) {
        boolean leader = tryLeader();

        NaiNode existing = nodeMapper.selectOne(
                new LambdaQueryWrapper<NaiNode>().eq(NaiNode::getNodeId, nodeId));
        NaiNode n = existing == null ? new NaiNode() : existing;
        n.setNodeId(nodeId);
        n.setHost(host);
        n.setPort(port);
        n.setVersion(version);
        n.setStartedAt(startedAt);
        n.setRole(leader ? ROLE_LEADER : ROLE_WORKER);
        n.setActiveRequests(activeRegistry.size());
        n.setCpuPercent(processCpuPercent());
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        n.setHeapUsedMb((int) (heap.getUsed() / BYTES_PER_MB));
        n.setHeapMaxMb(heap.getMax() > 0 ? (int) (heap.getMax() / BYTES_PER_MB) : null);
        if (labels != null && !labels.isBlank()) {
            n.setLabels(labels);
        }
        // 显式刷新末次心跳（MP 填充器在 update 时不覆盖实体携带的旧值·依赖 DB ON UPDATE 又会被显式列阻断）
        n.setUpdateDt(LocalDateTime.now());
        if (existing == null) {
            nodeMapper.insert(n);
        } else {
            nodeMapper.updateById(n);
        }
    }

    /** 抢/续 leader 标记：值为本节点则续期；空位 trySet 抢占；他人持有则 worker。 */
    private boolean tryLeader() {
        try {
            RBucket<String> bucket = redisson.getBucket(LEADER_KEY, StringCodec.INSTANCE);
            String holder = bucket.get();
            if (nodeId.equals(holder)) {
                bucket.set(nodeId, LEADER_TTL_SECONDS, TimeUnit.SECONDS);
                return true;
            }
            return holder == null && bucket.trySet(nodeId, LEADER_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis 不可达：本轮降级为 worker（leader 由其他节点或下轮续期保证·非致命）
            log.debug("leader 标记抢占失败，本轮降级为 worker: {}", e.getMessage());
            return false;
        }
    }

    private static Double processCpuPercent() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof OperatingSystemMXBean sun) {
                double load = sun.getProcessCpuLoad();
                return load < 0 ? null : Math.round(load * CPU_PERCENT_SCALE) / CPU_PERCENT_DIVISOR;
            }
        } catch (Exception ignored) {
            // 非 HotSpot 实现采不到进程 CPU
        }
        return null;
    }

    private static String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return FALLBACK_HOST;
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (daemon != null) {
            daemon.interrupt();
        }
        // 优雅下线：让出 leader 标记（TTL 兜底·非必需）
        try {
            RBucket<String> bucket = redisson.getBucket(LEADER_KEY, StringCodec.INSTANCE);
            if (nodeId != null && nodeId.equals(bucket.get())) {
                bucket.delete();
            }
        } catch (Exception ignored) {
            // Redis 不可达时靠 TTL 自然过期
        }
    }
}
