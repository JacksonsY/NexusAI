package com.nexusai.control.api.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.control.domain.project.registry.controller.ProjectAdminController;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.po.NaiProject;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 项目 M2M 令牌缓存（控制面 /admin/** 鉴权热路径·全内存非阻塞）。
 * <p>启动时全量灌入 active 项目（token → {@link NaiProject}），{@link ProjectAdminController} 增删改后调
 * {@link #broadcastReload()}（本节点重载 + RTopic 广播·各节点即时重载）。
 * 鉴权过滤器（{@link SaTokenConfigure}）只做 map 查找——绝不在 Netty 事件线程打 DB（项目数极少·全内存安全）。
 * <p>替代原单个静态 {@code nexusai.admin.token}：多项目凭证隔离 + 可独立吊销/轮转。
 * <p>🔴 多实例一致（对齐 sk- 令牌 {@code TOKEN_SYNC_TOPIC} H9）：增删改/轮转经 {@link GatewayConstants#PROJECT_TOKEN_SYNC_TOPIC}
 * 广播·各节点收讯即重载——否则仅本节点 reload、他节点仍用旧/已吊销令牌鉴权 {@code /admin/**} 直至重启。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectTokenCache {

    private final NaiProjectMapper projectMapper;
    private final RedissonClient redisson;
    private final AtomicReference<Map<String, NaiProject>> ref = new AtomicReference<>(Map.of());

    /** 启动：订阅跨实例广播（收讯即本地重载）+ 首次全量灌入。 */
    @PostConstruct
    public void init() {
        redisson.getTopic(GatewayConstants.PROJECT_TOKEN_SYNC_TOPIC, StringCodec.INSTANCE)
                .addListener(String.class, (channel, msg) -> {
                    reload();
                    log.info("ProjectTokenCache 已重载（跨实例广播）");
                });
        reload();
    }

    /** 本节点重载（增删改后 + 收到广播时调用·不再广播·防回环）。 */
    public void reload() {
        try {
            Map<String, NaiProject> map = projectMapper.selectList(
                            new LambdaQueryWrapper<NaiProject>().eq(NaiProject::getStatus, 1))
                    .stream()
                    .filter(p -> p.getToken() != null && !p.getToken().isBlank())
                    .collect(Collectors.toMap(NaiProject::getToken, p -> p, (a, b) -> a));
            ref.set(Map.copyOf(map));
            log.info("ProjectTokenCache 重载完成 active 项目数={}", map.size());
        } catch (Exception e) {
            log.error("ProjectTokenCache 重载失败，保留旧快照", e);
        }
    }

    /** 本节点重载 + 跨实例广播（项目令牌增删改/轮转后调用·各节点即时一致）。 */
    public void broadcastReload() {
        reload();
        try {
            redisson.getTopic(GatewayConstants.PROJECT_TOKEN_SYNC_TOPIC, StringCodec.INSTANCE).publish("reload");
        } catch (Exception e) {
            log.warn("ProjectTokenCache 广播失败（本节点已重载·他节点靠下次广播/重启收敛）: {}", e.toString());
        }
    }

    /** 令牌命中的 active 项目；未命中返回 null（不放行）。 */
    public NaiProject match(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return ref.get().get(token);
    }
}
