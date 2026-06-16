package com.nexusai.control.api.contract;

import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.event.ConfigChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 配置（渠道/路由/模型/渠道密钥）变更广播器：CRUD 写库后调用，使各网关节点路由/别名/价格本地缓存即时失效。
 * <p>与 token 同步同构（{@link com.nexusai.common.constant.GatewayConstants#TOKEN_SYNC_TOPIC}）——
 * <b>双发</b>：① 本节点进程内 {@link ConfigChangedEvent}（CONFIG 域，写入节点即时生效）；
 * ② Redis RTopic 跨实例广播（其他节点经网关侧监听器转回本地 {@code ConfigChangedEvent}）。
 * 不再仅靠 300s TTL 兜底——多实例下旧节点会在整个 TTL 周期内服务过期渠道/模型/价格。
 * <p>失败不阻塞写库：缓存失效信号丢失最坏退化为 TTL 收敛，绝不让一次广播异常回滚已落库的配置。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigChangePublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redisson;

    /** 触发配置缓存失效（本节点进程内事件 + 跨实例 RTopic 广播）；失败仅告警，下次 TTL/手动刷新兜底。 */
    public void publish() {
        long version = System.currentTimeMillis();
        try {
            eventPublisher.publishEvent(ConfigChangedEvent.config(version));
            redisson.getTopic(GatewayConstants.CONFIG_SYNC_TOPIC, StringCodec.INSTANCE)
                    .publish(String.valueOf(version));
        } catch (Exception e) {
            log.warn("配置变更广播失败（配置已落库，下次 TTL/手动刷新生效）", e);
        }
    }
}
