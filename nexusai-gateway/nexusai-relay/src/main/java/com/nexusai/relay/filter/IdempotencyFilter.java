package com.nexusai.relay.filter;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.relay.context.RelayContext;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import com.nexusai.runtime.ModuleToggles;
import com.nexusai.runtime.RuntimeSettings;

/**
 * 幂等防重（评审 H3）：
 * <ol>
 *   <li>客户端带 {@code Idempotency-Key} 头 → Redis SETNX 去重（{@code nai:idem:}·TTL），重复即拒（显式幂等）。</li>
 *   <li>无该头时，{@code nexusai.idempotent.auto-dedup-seconds}（<b>默认 5s</b>）窗口内按
 *       {@code tokenId+sha256(body)} 短窗 SETNX 去重，跨实例拦截 OpenAI/代理网络重发导致的双 freeze/双扣
 *       （settle 单次门闩是<b>进程内</b> AtomicBoolean·拦不住落到不同实例的重发，故必须靠此 Redis 跨实例去重）。
 *       默认 5s 足够覆盖网络层瞬时重发、又短到不误杀用户主动重发（regenerate 间隔通常 &gt; 5s）；置 0 可关。</li>
 * </ol>
 * <b>注意</b>：显式幂等仅对携带 {@code Idempotency-Key} 的客户端生效；body-hash 短窗是通用网络重发防护。
 *
 * @author nexusai
 */
@Component
@RequiredArgsConstructor
public class IdempotencyFilter implements RelayFilter {

    private static final String KEY_PREFIX = "nai:idem:";
    private static final String DEDUP_PREFIX = "nai:dedup:";
    private static final String IN_FLIGHT = "IN_FLIGHT:";
    private static final String DONE = "DONE:";
    private static final String FAILED = "FAILED:";
    /** 幂等键 TTL 下限（秒·防配置过小致终态键过早失效失去防重作用）。 */
    private static final long MIN_TTL_SECONDS = 10L;

    private final RedissonClient redisson;
    /** 模块管理页运行时开关（idempotency·关闭=跳过幂等/去重检查） */
    private final ModuleToggles moduleToggles;
    /** 模块设置抽屉参数热读（5s 生效·回退 yml 默认） */
    private final RuntimeSettings runtimeSettings;

    @Value("${nexusai.idempotent.ttl-seconds:600}")
    private long ttlSeconds;

    /** 无幂等头时的 body-hash 自动去重窗口（秒）·默认 5s 挡网络层重发双扣·0=关闭。 */
    @Value("${nexusai.idempotent.auto-dedup-seconds:5}")
    private long autoDedupSeconds;

    @Override
    public void filter(RelayContext ctx, ServerWebExchange exchange) {
        if (!moduleToggles.enabled("idempotency", true)) {
            return;
        }
        String key = exchange.getRequest().getHeaders().getFirst(GatewayConstants.IDEMPOTENCY_KEY_HEADER);
        if (key != null && !key.isBlank()) {
            long ttl = Math.max(MIN_TTL_SECONDS, runtimeSettings.getInt("module.idempotency.ttl_seconds", (int) ttlSeconds));
            // 🔴 跨租户隔离：显式幂等键按 tokenId 命名空间隔离，杜绝不同项目/Key 使用相同 Idempotency-Key 值时
            // 互相误判为重复（跨租户碰撞/DoS）。鉴权(order 10)先于本过滤器(order 15)，token 必非空。
            String scope = ctx.getToken() != null ? ctx.getToken().getId() + ":" : "";
            String redisKey = KEY_PREFIX + scope + key.trim();
            RBucket<String> bucket = redisson.getBucket(redisKey, StringCodec.INSTANCE);
            String value = IN_FLIGHT + ctx.getRequestId();
            if (!bucket.trySet(value, ttl, TimeUnit.SECONDS)) {
                String existing = bucket.get();
                if (existing != null && existing.startsWith(FAILED)) {
                    bucket.delete();
                    if (bucket.trySet(value, ttl, TimeUnit.SECONDS)) {
                        ctx.setIdempotencyRedisKey(redisKey);
                        ctx.setIdempotencyTtlSeconds(ttl);
                        return;
                    }
                    existing = bucket.get();
                }
                String state = existing == null ? "unknown"
                        : (existing.startsWith(DONE) ? "done"
                        : (existing.startsWith(IN_FLIGHT) ? "in_flight" : "occupied"));
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST,
                        "重复请求（幂等键已存在，state=" + state + "）: " + key);
            }
            ctx.setIdempotencyRedisKey(redisKey);
            ctx.setIdempotencyTtlSeconds(ttl);
            return;
        }
        // 无显式幂等头：按需开启 body-hash 短窗去重，挡网络层重发双扣
        long dedup = runtimeSettings.getInt("module.idempotency.auto_dedup_seconds", (int) autoDedupSeconds);
        if (dedup > 0 && ctx.getToken() != null && ctx.getBody() != null) {
            String dedupKey = DEDUP_PREFIX + ctx.getToken().getId() + ':' + sha256(ctx.getBody());
            RBucket<String> bucket = redisson.getBucket(dedupKey, StringCodec.INSTANCE);
            if (!bucket.trySet(ctx.getRequestId(), dedup, TimeUnit.SECONDS)) {
                throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "重复请求（短窗 body 去重·疑似网络重发）");
            }
        }
    }

    /**
     * 显式幂等键终态转换：2xx 完成才保留 DONE；异常/取消/非 2xx 释放为 FAILED，下一次同 key 可立即重试。
     * 自动 body-hash 短窗只做瞬时防重，不进入终态状态机。
     */
    public void finish(RelayContext ctx, boolean success) {
        if (ctx == null || ctx.getIdempotencyRedisKey() == null) {
            return;
        }
        try {
            RBucket<String> bucket = redisson.getBucket(ctx.getIdempotencyRedisKey(), StringCodec.INSTANCE);
            String expected = IN_FLIGHT + ctx.getRequestId();
            String existing = bucket.get();
            if (!expected.equals(existing)) {
                return;
            }
            long ttl = Math.max(MIN_TTL_SECONDS, ctx.getIdempotencyTtlSeconds());
            if (success) {
                bucket.set(DONE + ctx.getRequestId(), ttl, TimeUnit.SECONDS);
            } else {
                bucket.set(FAILED + ctx.getRequestId(), ttl, TimeUnit.SECONDS);
            }
        } catch (Exception ignore) {
            // 幂等终态转换失败不影响主请求；最差由 TTL 兜底释放。
        }
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(body));
        }
    }

    @Override
    public int getOrder() {
        return 15;   // 鉴权(10) 之后、解析(20) 之前
    }
}
