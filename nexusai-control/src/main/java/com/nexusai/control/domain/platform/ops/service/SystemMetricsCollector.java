package com.nexusai.control.domain.platform.ops.service;

import com.nexusai.control.domain.platform.ops.JobHeartbeats;
import com.nexusai.control.domain.platform.ops.algo.TrafficMath;
import com.nexusai.persistence.mapper.NaiNodeMapper;
import com.nexusai.persistence.po.NaiNode;
import com.sun.management.OperatingSystemMXBean;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统资源采集器（JVM 进程视角 + 依赖连通）：
 * CPU/堆/线程 + DB/Redis 连通探测 + 集群节点判活（90s）+ Hikari 连接池水位 + 后台任务心跳，
 * 与日志聚合无关的环境采集独立于 {@code OpsDashboardService}。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemMetricsCollector {

    /** 集群节点判活窗口（秒·超过即视为离线） */
    private static final long NODE_ONLINE_WINDOW_SECONDS = 90;
    /** Redis 连通探测键 */
    private static final String REDIS_PING_KEY = "nai:ops:ping";
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final NaiNodeMapper nodeMapper;
    private final RedissonClient redisson;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public Map<String, Object> snapshot(LocalDateTime now) {
        Map<String, Object> sys = new LinkedHashMap<>();
        var os = ManagementFactory.getOperatingSystemMXBean();
        double cpu = os instanceof OperatingSystemMXBean sun ? sun.getCpuLoad() : -1;
        sys.put("cpuUsagePercent", cpu >= 0 ? TrafficMath.round2(cpu * 100) : null);
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        sys.put("heapUsedMb", heapUsed / BYTES_PER_MB);
        sys.put("heapMaxMb", rt.maxMemory() / BYTES_PER_MB);
        sys.put("memoryUsagePercent", TrafficMath.round2((double) heapUsed / rt.maxMemory() * 100));
        sys.put("threadCount", ManagementFactory.getThreadMXBean().getThreadCount());
        boolean dbOk;
        try {
            nodeMapper.selectCount(null);
            dbOk = true;
        } catch (Exception e) {
            // 探测失败即判 DB 不可用（false 本身就是健康结论·非吞错）；记 debug 便于排查
            log.debug("DB 连通探测失败: {}", e.toString());
            dbOk = false;
        }
        sys.put("dbOk", dbOk);
        boolean redisOk;
        try {
            redisson.getBucket(REDIS_PING_KEY).isExists();
            redisOk = true;
        } catch (Exception e) {
            // 探测失败即判 Redis 不可用（false 本身就是健康结论·非吞错）；记 debug 便于排查
            log.debug("Redis 连通探测失败: {}", e.toString());
            redisOk = false;
        }
        sys.put("redisOk", redisOk);
        List<NaiNode> nodes = nodeMapper.selectList(null);
        long online = nodes.stream().filter(n -> n.getUpdateDt() != null
                && Duration.between(n.getUpdateDt(), now).getSeconds() <= NODE_ONLINE_WINDOW_SECONDS).count();
        sys.put("onlineNodes", online);
        sys.put("totalNodes", nodes.size());
        // DB 连接池利用率（HikariCP MXBean，非 Hikari 实现时缺省）
        try {
            DataSource ds = dataSourceProvider.getIfAvailable();
            if (ds != null && ds.isWrapperFor(HikariDataSource.class)) {
                var hikari = ds.unwrap(HikariDataSource.class);
                var pool = hikari.getHikariPoolMXBean();
                if (pool != null) {
                    Map<String, Object> dbPool = new LinkedHashMap<>();
                    dbPool.put("active", pool.getActiveConnections());
                    dbPool.put("idle", pool.getIdleConnections());
                    dbPool.put("total", pool.getTotalConnections());
                    dbPool.put("max", hikari.getMaximumPoolSize());
                    dbPool.put("awaiting", pool.getThreadsAwaitingConnection());
                    sys.put("dbPool", dbPool);
                }
            }
        } catch (Exception ignore) {
            // 连接池信息为可选增强，取不到不影响快照
        }
        // 后台守护任务心跳
        sys.put("jobs", JobHeartbeats.snapshot());
        return sys;
    }
}
