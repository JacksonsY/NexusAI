package com.nexusai.router.cache;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusai.common.event.ConfigChangedEvent;
import com.nexusai.observe.cache.CacheRegistry;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 路由热数据本地缓存（Caffeine·失效驱动）。集中缓存两级调度所需的：
 * <ul>
 *   <li>ability 列表 by (group|model) —— 第一级渠道选择，不每请求打 DB；</li>
 *   <li>channel by id —— 渠道实体；</li>
 *   <li>key 列表 by channelId —— 第二级渠道密钥选号。</li>
 * </ul>
 * <p><b>一致性模型</b>：失效以 {@link ConfigChangedEvent}（CONFIG 域）<b>整体清空为主</b>（配置变更本节点即时生效），
 * TTL 仅作<b>漏播兜底 backstop</b>（取长·默认 300s）——稳态全为缓存命中，<b>不实时查库</b>（honor 铁律②），消除短 TTL 频繁 miss→热路径同步查库。
 * <p>key 行<b>只含配置</b>（凭证/weight/并发上限）：运行时熔断/健康态已分离到 {@link com.nexusai.router.health.RoutingHealthStat}
 * + {@link com.nexusai.router.health.RuntimeStateStore}（跨实例 RTopic 广播），故 key 缓存与 ability/channel 同 TTL，无需最短。
 * <p>缓存 {@link Optional}/空列表（负缓存），避免缺失组合反复打 DB；新增配置经 CONFIG 事件清空后即可见。
 *
 * @author nexusai
 */
@Slf4j
@Component
public class RouteCache {

    private final NaiAbilityMapper abilityMapper;
    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper keyMapper;

    private final Cache<String, List<NaiAbility>> abilityCache;
    private final Cache<Long, Optional<NaiChannel>> channelCache;
    private final Cache<Long, List<NaiChannelKey>> keyCache;

    public RouteCache(NaiAbilityMapper abilityMapper,
                      NaiChannelMapper channelMapper,
                      NaiChannelKeyMapper keyMapper,
                      CacheRegistry cacheRegistry,
                      @Value("${nexusai.cache.ability.ttl-seconds:300}") long abilityTtl,
                      @Value("${nexusai.cache.ability.max-size:5000}") long abilityMax,
                      @Value("${nexusai.cache.channel.ttl-seconds:300}") long channelTtl,
                      @Value("${nexusai.cache.channel.max-size:2000}") long channelMax,
                      @Value("${nexusai.cache.key-pool.ttl-seconds:300}") long keyTtl,
                      @Value("${nexusai.cache.key-pool.max-size:2000}") long keyMax) {
        this.abilityMapper = abilityMapper;
        this.channelMapper = channelMapper;
        this.keyMapper = keyMapper;
        this.abilityCache = Caffeine.newBuilder().recordStats()
                .expireAfterWrite(Duration.ofSeconds(abilityTtl)).maximumSize(abilityMax).build();
        this.channelCache = Caffeine.newBuilder().recordStats()
                .expireAfterWrite(Duration.ofSeconds(channelTtl)).maximumSize(channelMax).build();
        this.keyCache = Caffeine.newBuilder().recordStats()
                .expireAfterWrite(Duration.ofSeconds(keyTtl)).maximumSize(keyMax).build();
        cacheRegistry.register("route:ability", abilityCache);
        cacheRegistry.register("route:channel", channelCache);
        cacheRegistry.register("route:key", keyCache);
    }

    /** 第一级：启用的 ability 列表（group|model 维度）。 */
    public List<NaiAbility> abilities(String group, String model) {
        String k = group + '|' + model;
        return abilityCache.get(k, key -> abilityMapper.selectList(new LambdaQueryWrapper<NaiAbility>()
                .eq(NaiAbility::getGroupCode, group)
                .eq(NaiAbility::getModel, model)
                .eq(NaiAbility::getEnabled, Boolean.TRUE)));
    }

    /** 渠道实体（by id）。 */
    public Optional<NaiChannel> channel(Long channelId) {
        return channelCache.get(channelId, id -> Optional.ofNullable(channelMapper.selectById(id)));
    }

    /** 第二级：渠道内启用的 Key 列表（by channelId）。 */
    public List<NaiChannelKey> keys(Long channelId) {
        return keyCache.get(channelId, id -> keyMapper.selectList(new LambdaQueryWrapper<NaiChannelKey>()
                .eq(NaiChannelKey::getChannelId, id)
                .eq(NaiChannelKey::getStatus, 1)));
    }

    /** 配置变更 → 整体清空路由缓存（CONFIG/ALL 域）。 */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.hits(ConfigChangedEvent.Scope.CONFIG)) {
            invalidateAll();
            log.info("RouteCache 已清空（CONFIG version={}）", event.version());
        }
    }

    public void invalidateAll() {
        abilityCache.invalidateAll();
        channelCache.invalidateAll();
        keyCache.invalidateAll();
    }
}
