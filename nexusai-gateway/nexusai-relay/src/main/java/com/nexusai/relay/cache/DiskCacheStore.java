package com.nexusai.relay.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import jakarta.annotation.PreDestroy;
import com.nexusai.runtime.RuntimeSettings;
import com.nexusai.runtime.ModuleMetricsRegistry;
import com.nexusai.observe.cache.CacheRegistry;

/**
 * 磁盘缓存：超过阈值的响应缓存大值落本地盘而非 Redis
 * （防大体撑爆 Redis 内存/网络往返放大），Redis 只存文件名指针。
 * <ul>
 *   <li>目录 {@code {java.io.tmpdir}/nexusai-disk-cache}；文件名 = 缓存键 hash（外部已 SHA-256）</li>
 *   <li>TTL：与响应缓存同 TTL；守护线程每 5 分钟扫目录删超龄；读时过期惰性删</li>
 *   <li>容量闸：目录总字节超 {@code max-bytes}（默认 1GiB）时拒写（不淘汰旧文件·下轮清理回收）</li>
 *   <li>统计：文件数/总字节/写入/命中/未命中（缓存监控页磁盘缓存卡）</li>
 * </ul>
 *
 * @author nexusai
 */
@Slf4j
@Component
public class DiskCacheStore {

    private final Path dir;
    private final long ttlMillis;
    /** yml 默认；模块设置抽屉 module.disk_cache.max_mb 经 RuntimeSettings 热读覆盖 */
    private final long maxBytes;
    private final RuntimeSettings runtimeSettings;
    private final LongAdder writes = new LongAdder();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong fileCount = new AtomicLong();
    private final ScheduledExecutorService cleaner;

    public DiskCacheStore(@Value("${nexusai.cache.disk.dir:}") String dirConfig,
                          @Value("${nexusai.cache.response.ttl-seconds:300}") long ttlSeconds,
                          @Value("${nexusai.cache.disk.max-bytes:1073741824}") long maxBytes,
                          RuntimeSettings runtimeSettings,
                          ModuleMetricsRegistry moduleMetrics,
                          CacheRegistry cacheRegistry) {
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.maxBytes = maxBytes;
        this.runtimeSettings = runtimeSettings;
        // 指标自注册（控制面经注册表读，不直依赖本类）：模块抽屉 4 指标 + 缓存监控页磁盘卡原始 stats
        moduleMetrics.register("disk_cache", () -> List.of(
                ModuleMetricsRegistry.metric("写入", writes.sum()),
                ModuleMetricsRegistry.metric("命中", hits.sum()),
                ModuleMetricsRegistry.metric("未命中", misses.sum()),
                ModuleMetricsRegistry.metric("占用字节", totalBytes.get())));
        cacheRegistry.registerStats("disk", this::stats);
        Path base = dirConfig == null || dirConfig.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "nexusai-disk-cache")
                : Path.of(dirConfig);
        this.dir = base;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("磁盘缓存目录创建失败（磁盘缓存停用）: {}", e.toString());
        }
        refreshUsage();
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "disk-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleWithFixedDelay(this::cleanup, 5, 5, TimeUnit.MINUTES);
    }

    /** 优雅停机：关闭清理调度线程，避免容器关闭时残留。 */
    @PreDestroy
    public void shutdown() {
        cleaner.shutdownNow();
    }

    private long effectiveMaxBytes() {
        return runtimeSettings.getInt("module.disk_cache.max_mb", (int) (maxBytes / 1024 / 1024)) * 1024L * 1024L;
    }

    /** 写文件（容量闸内）。成功返回 true。 */
    public boolean put(String name, byte[] data) {
        if (data == null || data.length == 0 || totalBytes.get() + data.length > effectiveMaxBytes()) {
            return false;
        }
        Path f = dir.resolve(name);
        try {
            // 覆盖既有键时只计增量：减旧文件字节、不增 fileCount，避免重复计数虚增 totalBytes 误触容量闸锁死写入
            long oldSize = Files.exists(f) ? Files.size(f) : -1L;
            Files.write(f, data);
            writes.increment();
            if (oldSize < 0) {
                totalBytes.addAndGet(data.length);
                fileCount.incrementAndGet();
            } else {
                totalBytes.addAndGet(data.length - oldSize);
            }
            return true;
        } catch (IOException e) {
            log.warn("磁盘缓存写失败: {}", e.toString());
            return false;
        }
    }

    /** 读文件（过期/缺失惰性删并 miss）。 */
    public byte[] get(String name) {
        Path f = dir.resolve(name);
        try {
            if (!Files.exists(f)) {
                misses.increment();
                return null;
            }
            if (System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() > ttlMillis) {
                deleteQuietly(f);
                misses.increment();
                return null;
            }
            byte[] data = Files.readAllBytes(f);
            hits.increment();
            return data;
        } catch (IOException e) {
            misses.increment();
            return null;
        }
    }

    /** 统计快照（缓存监控页）。 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir.toString());
        m.put("fileCount", fileCount.get());
        m.put("totalBytes", totalBytes.get());
        m.put("maxBytes", effectiveMaxBytes());
        m.put("writes", writes.sum());
        m.put("hits", hits.sum());
        m.put("misses", misses.sum());
        m.put("ttlSeconds", ttlMillis / 1000);
        return m;
    }

    public long clearAll() {
        long n = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                if (deleteQuietly(f)) {
                    n++;
                }
            }
        } catch (IOException ignored) {
            // 目录不可读
        }
        refreshUsage();
        return n;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                try {
                    if (now - Files.getLastModifiedTime(f).toMillis() > ttlMillis) {
                        deleteQuietly(f);
                    }
                } catch (IOException ignored) {
                    // 单文件失败不影响整轮
                }
            }
        } catch (IOException ignored) {
            // 目录不可读
        }
        refreshUsage();
    }

    private void refreshUsage() {
        long bytes = 0;
        long count = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                try {
                    bytes += Files.size(f);
                    count++;
                } catch (IOException ignored) {
                    // skip
                }
            }
        } catch (IOException ignored) {
            // 目录不可读
        }
        totalBytes.set(bytes);
        fileCount.set(count);
    }

    private boolean deleteQuietly(Path f) {
        try {
            long size = Files.size(f);
            if (Files.deleteIfExists(f)) {
                totalBytes.addAndGet(-size);
                fileCount.decrementAndGet();
                return true;
            }
        } catch (IOException ignored) {
            // skip
        }
        return false;
    }
}
