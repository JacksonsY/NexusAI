package com.nexusai.router.cache;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.event.ConfigChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 配置变更跨实例订阅者：监听 {@link GatewayConstants#CONFIG_SYNC_TOPIC}（控制面 {@code ConfigChangePublisher} 广播），
 * 收到后在本节点转发一个进程内 {@link ConfigChangedEvent}（CONFIG 域），
 * 使本节点已有的本地缓存监听器（{@link RouteCache}、{@code ModelAliasResolver}、{@code ModelPriceService} 等）即时失效。
 * <p>与 {@code ApiKeyAuthService} 订阅 {@link GatewayConstants#TOKEN_SYNC_TOPIC} 同构：写库节点靠本地事件即时生效，
 * 其余节点靠本订阅器把远端信号转回本地事件——共同消除"多实例下旧节点服务过期配置直到 TTL"。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class ConfigSyncSubscriber {

    public ConfigSyncSubscriber(RedissonClient redisson, ApplicationEventPublisher eventPublisher) {
        redisson.getTopic(GatewayConstants.CONFIG_SYNC_TOPIC, StringCodec.INSTANCE)
                .addListener(String.class, (channel, version) -> {
                    long v = parseVersion(version);
                    eventPublisher.publishEvent(ConfigChangedEvent.config(v));
                    log.info("配置缓存失效（跨实例广播 version={}）", v);
                });
    }

    private static long parseVersion(String version) {
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }
}
