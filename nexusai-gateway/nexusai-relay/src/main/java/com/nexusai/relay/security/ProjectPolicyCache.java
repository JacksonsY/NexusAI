package com.nexusai.relay.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.po.NaiProject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import com.nexusai.observe.cache.CacheRegistry;

/**
 * 项目访问策略缓存（流量面热路径·by project_code·Caffeine 短 TTL，与 {@link ApiKeyAuthService} 同范式）。
 * <p>缓存<b>解析态</b> {@link ProjectPolicy}（非 PO·免每请求 JSON 解析）；不存在/无归属项目缓存 UNRESTRICTED。
 * 失效：短 TTL 兜底（策略变更 ≤ TTL 秒生效·控制面低频操作可接受）——不引控制面→流量面广播耦合。
 * <p>策略跟随项目<b>存在</b>而非激活态：归档项目仅吊销其控制面 M2M 凭证（{@code ProjectTokenCache} 只灌 active），
 * 其下 sk- Key 的流量约束不因归档而放开（防归档=越权逃逸）。
 *
 * @author nexusai
 */
@Slf4j
@Service
public class ProjectPolicyCache {

    private final NaiProjectMapper projectMapper;
    private final Cache<String, ProjectPolicy> cache;

    public ProjectPolicyCache(NaiProjectMapper projectMapper,
                              CacheRegistry cacheRegistry,
                              @Value("${nexusai.cache.project-policy.ttl-seconds:60}") long ttlSeconds) {
        this.projectMapper = projectMapper;
        this.cache = Caffeine.newBuilder().recordStats()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(1000)
                .build();
        cacheRegistry.register("auth:project-policy", cache);
    }

    /** 按项目标识取解析态策略；projectCode 空/项目不存在 → 不限策略。 */
    public ProjectPolicy byCode(String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return ProjectPolicy.UNRESTRICTED;
        }
        return cache.get(projectCode, code -> {
            NaiProject p = Optional.ofNullable(projectMapper.selectOne(
                            new LambdaQueryWrapper<NaiProject>().eq(NaiProject::getProjectCode, code)))
                    .orElse(null);
            if (p == null) {
                log.warn("sk- Key 归属的项目不存在 projectCode={}（按不限策略放行）", code);
            }
            return ProjectPolicy.of(p);
        });
    }
}
