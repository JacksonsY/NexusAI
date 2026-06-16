package com.nexusai.relay.affinity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 摘要会话存储：把「逐消息摘要链 → 会话 id」短 TTL 记忆，
 * 解决多轮对话亲和键跨轮失配——第 N+1 轮请求的摘要链是第 N 轮链的<b>前缀扩展</b>，
 * {@link #find} 从完整链逐段截断找最长匹配即可命中旧会话；{@link #save} 写新链删旧链滚动。
 * <p>键 = {@code tokenId|digestChain}；值 = 会话 id（随机短串·亲和键素材）。单实例内存（与 StickyAffinity 同级）。
 *
 * @author nexusai
 */
@Component
public class DigestSessionStore {

    /** 单链最大回溯段数（防超长对话逐段截断 O(n) 失控） */
    private static final int MAX_BACKTRACK = 64;

    private final Cache<String, String> cache;
    private final AtomicLong seq = new AtomicLong();

    public DigestSessionStore(@Value("${nexusai.pool.sticky.ttl-seconds:300}") long ttlSeconds,
                              @Value("${nexusai.pool.sticky.max-size:50000}") long maxSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .build();
    }

    /** 查找+滚动：最长前缀匹配旧会话则迁移到新链并返回旧会话 id；未命中则新建会话 id 存新链。 */
    public String resolveSession(long tokenId, String digestChain) {
        if (digestChain == null || digestChain.isEmpty()) {
            return null;
        }
        String ns = tokenId + "|";
        // 完整链直查（同轮重试）
        String hit = cache.getIfPresent(ns + digestChain);
        if (hit != null) {
            cache.put(ns + digestChain, hit);   // 刷新 TTL
            return hit;
        }
        // 逐段截断回溯（多轮追加：新链 = 旧链 + 新段）
        String chain = digestChain;
        for (int i = 0; i < MAX_BACKTRACK; i++) {
            int cut = chain.lastIndexOf('-');
            if (cut <= 0) {
                break;
            }
            chain = chain.substring(0, cut);
            String session = cache.getIfPresent(ns + chain);
            if (session != null) {
                cache.invalidate(ns + chain);             // 删旧链
                cache.put(ns + digestChain, session);     // 滚动到新链
                return session;
            }
        }
        // 未命中：新建会话
        String session = Long.toHexString(System.nanoTime()) + Long.toHexString(seq.incrementAndGet());
        cache.put(ns + digestChain, session);
        return session;
    }
}
