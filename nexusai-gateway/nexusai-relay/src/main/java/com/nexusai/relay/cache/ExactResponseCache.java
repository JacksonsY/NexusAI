package com.nexusai.relay.cache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import com.nexusai.runtime.RuntimeSettings;
import com.nexusai.runtime.ModuleToggles;

/**
 * 精确匹配响应缓存（默认实现·Redis）：key={@code nai:rcache:<sha256(model+body)>}·value=响应字节·TTL 可配。
 * <p>适用幂等/确定性请求（temperature=0 等）；命中省上游调用 + USD 额度。<b>语义（向量）匹配</b>是同 SPI 的扩展。
 * 仅 {@code nexusai.cache.response.enabled=true} 装配。
 *
 * @author nexusai
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nexusai.cache.response", name = "enabled", havingValue = "true")
public class ExactResponseCache implements ResponseCache {

    private static final String PREFIX = "nai:rcache:";

    /** 落盘指针标记前缀（Redis 值以此开头则表示大体已落盘·后跟磁盘条目名）。 */
    private static final String DISK_POINTER_MARKER = "@disk:";
    /** 超过此字节数的响应落盘（防大体撑爆 Redis），Redis 只存指针 */
    private static final byte[] DISK_POINTER_PREFIX = DISK_POINTER_MARKER.getBytes(StandardCharsets.UTF_8);
    /** KB→字节换算因子（磁盘阈值配置用）。 */
    private static final long KB = 1024L;

    private final RedissonClient redisson;
    /** yml 默认；模块设置抽屉写 KV 后经 RuntimeSettings 热读覆盖（5s 生效） */
    private final long ttlSeconds;
    private final long diskThresholdBytes;
    private final RuntimeSettings runtimeSettings;
    private final DiskCacheStore diskStore;
    private final ModuleToggles moduleToggles;

    public ExactResponseCache(RedissonClient redisson,
                              DiskCacheStore diskStore,
                              ModuleToggles moduleToggles,
                              RuntimeSettings runtimeSettings,
                              @Value("${nexusai.cache.response.ttl-seconds:300}") long ttlSeconds,
                              @Value("${nexusai.cache.response.disk-threshold-bytes:262144}") long diskThresholdBytes) {
        this.runtimeSettings = runtimeSettings;
        this.redisson = redisson;
        this.diskStore = diskStore;
        this.moduleToggles = moduleToggles;
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.diskThresholdBytes = diskThresholdBytes;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public byte[] get(String scope, String model, byte[] requestBody) {
        try {
            byte[] value = bucket(scope, model, requestBody).get();
            if (value != null && isDiskPointer(value)) {
                String name = new String(value, DISK_POINTER_PREFIX.length,
                        value.length - DISK_POINTER_PREFIX.length, StandardCharsets.UTF_8);
                return diskStore.get(name);
            }
            return value;
        } catch (Exception e) {
            log.warn("响应缓存读失败（降级直查上游）: {}", e.toString());
            return null;
        }
    }

    @Override
    public void put(String scope, String model, byte[] requestBody, byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return;
        }
        try {
            long ttl = effectiveTtlSeconds();
            if (responseBody.length > effectiveDiskThresholdBytes()) {
                if (!moduleToggles.enabled("disk_cache", true)) {
                    return;   // 磁盘缓存关闭：大体放弃缓存（不降级塞 Redis）
                }
                String name = key(scope, model, requestBody);
                if (diskStore.put(name, responseBody)) {
                    byte[] pointer = (DISK_POINTER_MARKER + name).getBytes(StandardCharsets.UTF_8);
                    bucket(scope, model, requestBody).set(pointer, Duration.ofSeconds(ttl));
                }
                return;   // 容量闸拒写则放弃缓存（不降级塞 Redis 大体）
            }
            bucket(scope, model, requestBody).set(responseBody, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("响应缓存写失败（不影响主链）: {}", e.toString());
        }
    }

    private long effectiveTtlSeconds() {
        return Math.max(1, runtimeSettings.getInt("module.response_cache.ttl_seconds", (int) ttlSeconds));
    }

    private long effectiveDiskThresholdBytes() {
        return runtimeSettings.getInt("module.response_cache.disk_threshold_kb", (int) (diskThresholdBytes / KB)) * KB;
    }

    private static boolean isDiskPointer(byte[] value) {
        if (value.length <= DISK_POINTER_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < DISK_POINTER_PREFIX.length; i++) {
            if (value[i] != DISK_POINTER_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    private RBucket<byte[]> bucket(String scope, String model, byte[] requestBody) {
        return redisson.getBucket(PREFIX + key(scope, model, requestBody), ByteArrayCodec.INSTANCE);
    }

    private static String key(String scope, String model, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // 🔴 scope（租户边界·项目码）必须入 key·防跨租户命中彼此缓存（数据泄露 + 免结算白嫖）
            md.update((scope == null ? "" : scope).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update((model == null ? "" : model).getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JRE 标配·永不缺失；绝不降级为低熵 hashCode 键（会致跨租户缓存命中→数据泄露/免结算）
            throw new IllegalStateException("SHA-256 不可用，无法构建缓存键", e);
        }
    }
}
