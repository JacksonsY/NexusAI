package com.nexusai.relay.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.observe.cache.CacheRegistry;
import com.nexusai.persistence.mapper.NaiWorkspaceMapper;
import com.nexusai.persistence.po.NaiWorkspace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 工作区限流配置缓存（流量面热路径·by workspaceId·Caffeine 短 TTL·与 {@link ProjectPolicyCache} 同范式）：
 * 缓存工作区级 RPM/TPM 闸值（免每请求查 nai_workspace）；不存在/无归属返回 {@link Limits#NONE}(0,0=不限)。
 * 失效靠短 TTL 兜底（限流配置变更低频·可接受 ≤TTL 秒延迟）。
 *
 * @author nexusai
 */
@Service
public class WorkspaceLimitsCache {

    /** 工作区限流闸值（rpm/tpm·0=不限）。 */
    public record Limits(int rpm, int tpm) {
        public static final Limits NONE = new Limits(0, 0);
    }

    private final NaiWorkspaceMapper workspaceMapper;
    private final Cache<Long, Limits> cache;

    public WorkspaceLimitsCache(NaiWorkspaceMapper workspaceMapper,
                                CacheRegistry cacheRegistry,
                                @Value("${nexusai.cache.workspace-limits.ttl-seconds:60}") long ttlSeconds) {
        this.workspaceMapper = workspaceMapper;
        this.cache = Caffeine.newBuilder().recordStats()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(5000)
                .build();
        cacheRegistry.register("limit:workspace", cache);
    }

    /** 按工作区 id 取限流闸值；null/不存在 → NONE（不限）。 */
    public Limits byId(Long workspaceId) {
        if (workspaceId == null) {
            return Limits.NONE;
        }
        return cache.get(workspaceId, id -> {
            NaiWorkspace w = workspaceMapper.selectById(id);
            if (w == null) {
                return Limits.NONE;
            }
            return new Limits(
                    w.getRpmLimit() == null ? 0 : w.getRpmLimit(),
                    w.getTpmLimit() == null ? 0 : w.getTpmLimit());
        });
    }
}
