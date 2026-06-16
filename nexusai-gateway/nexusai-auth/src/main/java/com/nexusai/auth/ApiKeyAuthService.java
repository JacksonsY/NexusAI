package com.nexusai.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.event.ConfigChangedEvent;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.persistence.mapper.NaiTokenMapper;
import com.nexusai.persistence.po.NaiToken;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import com.nexusai.observe.cache.CacheRegistry;

/**
 * API Key 鉴权：取 Bearer sk-xxx → SHA-256 → 查 nai_token（只读快照·常量时间比对）。
 * <p>命中 Caffeine 短 TTL 本地缓存（by key_hash）减 DB。缓存的仅是 <b>Key 元数据</b>
 * （group/status/expiry/limits）——余额始终走 Redis（QuotaLedger 真理源），不读缓存 PO。
 * <p>失效：① 短 TTL 兜底跨节点一致性；② 本节点 Key 同步/吊销发 {@link ConfigChangedEvent}（TOKEN 域）即时清空。
 * <b>状态/过期每请求复核</b>：缓存只省 DB 查，stop/expire 判断不缓存。
 *
 * @author nexusai
 */
@Slf4j
@Service
public class ApiKeyAuthService {

    /** 负缓存哨兵：DB 查无此 Key 时缓存此实例，避免无效 Key 风暴每请求穿透打 DB（DoS 放大）。
     *  新建/同步 Key 会经 TOKEN 域 {@link ConfigChangedEvent} + RTopic 广播整体清空，负缓存不会滞留阻断新 Key。 */
    private static final NaiToken NEGATIVE = new NaiToken();

    private final NaiTokenMapper tokenMapper;
    private final Cache<String, NaiToken> tokenCache;

    public ApiKeyAuthService(NaiTokenMapper tokenMapper,
                             RedissonClient redisson,
                             CacheRegistry cacheRegistry,
                             @Value("${nexusai.cache.token.ttl-seconds:60}") long ttlSeconds,
                             @Value("${nexusai.cache.token.max-size:10000}") long maxSize) {
        this.tokenMapper = tokenMapper;
        this.tokenCache = Caffeine.newBuilder().recordStats()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .build();
        cacheRegistry.register("auth:token", tokenCache);
        // 评审 H9：token 吊销跨实例广播（RTopic）→ 各节点即时清本地缓存，不靠 60s TTL 兜底放行已吊销 key。
        redisson.getTopic(GatewayConstants.TOKEN_SYNC_TOPIC, StringCodec.INSTANCE)
                .addListener(String.class, (channel, tokenId) -> {
                    tokenCache.invalidateAll();
                    log.info("token 缓存已清空（跨实例广播 tokenId={}）", tokenId);
                });
    }

    public NaiToken authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(GatewayConstants.BEARER_PREFIX)) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "缺少 Authorization: Bearer sk-...");
        }
        String apiKey = authHeader.substring(GatewayConstants.BEARER_PREFIX.length()).trim();
        if (apiKey.isEmpty()) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED);
        }
        String hash = sha256(apiKey);
        // get(loader)：命中返回缓存；未命中查 DB 后写入。查无此 Key 写入 NEGATIVE 哨兵（负缓存·挡无效 Key 风暴穿透）。
        NaiToken token = tokenCache.get(hash, h -> {
            NaiToken row = tokenMapper.selectOne(
                    new LambdaQueryWrapper<NaiToken>().eq(NaiToken::getKeyHash, h));
            return row == null ? NEGATIVE : row;
        });
        if (token == null || token == NEGATIVE) {
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "API Key 无效");
        }
        // 状态/过期每请求复核（不依赖缓存时效）
        if (token.getStatus() == null || token.getStatus() != 1) {
            tokenCache.invalidate(hash);
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "API Key 已停用");
        }
        if (token.getExpiredTime() != null && token.getExpiredTime() > 0
                && token.getExpiredTime() < System.currentTimeMillis()) {
            tokenCache.invalidate(hash);
            throw GatewayException.of(GatewayErrorCode.UNAUTHORIZED, "API Key 已过期");
        }
        return token;
    }

    /** Key 同步/吊销 → 本节点清空 token 缓存（TOKEN/ALL 域）。跨节点由短 TTL 收敛。 */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.hits(ConfigChangedEvent.Scope.TOKEN)) {
            tokenCache.invalidateAll();
            log.info("token 缓存已清空（version={}）", event.version());
        }
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw GatewayException.of(GatewayErrorCode.INTERNAL_ERROR, "SHA-256 计算失败");
        }
    }
}
