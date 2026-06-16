package com.nexusai.router.health;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多实例熔断态下沉 + 即时广播（H6·始终启用·无 flag·对齐 token/config 的 RTopic 广播范式）。
 * <p>熔断决策是<b>正确性关键</b>的集群真理源：A 实例熔断某 Key/渠道，B 实例必须立即避让，否则坏 Key 要被每个实例各踩 N 次。
 * 实现：每次熔断①写 per-ns RMap {@code nai:ban:{ns}}（id→untilMs·供新实例 warm-start + 顺带清理过期项）
 * ②RTopic {@code nai:health:evt} 即时广播（各实例落到本地 {@link RoutingHealthStat#remoteBannedUntil}·热路径只读本地不查 Redis）。
 * <p><b>不下沉的两类（有意取舍）</b>：①健康分/延迟 EWMA——按各自流量量的指数滑动，跨实例合并数学上不良定义，各实例本地自收敛；
 * ②自适应学习 RPM——仅作打分软提示（硬 RPM 上限已由 {@code RateLimiterService} 的 Redisson RRateLimiter 跨实例兜住），
 * 各实例本地置信度门控学习 + 探测回升自愈，不跨实例同步（避免与探测回升冲突的 min-merge 再学习）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeStateStore {

    /** per-ns 熔断戳持久化（warm-start 用·id→untilMs） */
    private static final String BAN_MAP_PREFIX = "nai:ban:";
    /** 熔断即时广播主题（消息体 {@code ns:id:untilMs}） */
    private static final String EVENT_TOPIC = "nai:health:evt";

    private final RedissonClient redisson;

    /** 远端熔断事件订阅者（KeyHealthManager / ChannelHealthManager 注册·按各自 ns 过滤应用到本地镜像）。 */
    public interface BanListener {
        void onRemoteBan(String ns, long id, long untilMs);
    }

    private final List<BanListener> listeners = new CopyOnWriteArrayList<>();

    public void register(BanListener listener) {
        listeners.add(listener);
    }

    @PostConstruct
    public void subscribe() {
        redisson.getTopic(EVENT_TOPIC, StringCodec.INSTANCE)
                .addListener(String.class, (channel, msg) -> dispatch(msg));
    }

    /** 记熔断（写 RMap 持久化 + RTopic 即时广播·失败降级纯本地不影响主链）。 */
    public void recordBan(String ns, Long id, long untilMs) {
        if (id == null) {
            return;
        }
        // 🔴 offload 出调用线程：recordBan 经 health.recordFailure ← onUpstreamResponse 在 WebClient/Netty 事件
        // 循环触发，RMap.merge + RTopic.publish 是阻塞 Redis 往返，绝不能压在事件循环上（与 writeBack 的 publishOn
        // 同口径）。本节点本地熔断态已由 HealthManager 同步置好，此处仅跨实例下沉/广播·异步不影响本节点即时避让。
        Schedulers.boundedElastic().schedule(() -> {
            try {
                RMap<Long, Long> map = redisson.getMap(BAN_MAP_PREFIX + ns, LongCodec.INSTANCE);
                // 原子 merge 取大值：并发熔断时较小 untilMs 不会覆盖较大的（Redisson 服务端原子合并·非读-比较-写）
                map.merge(id, untilMs, Math::max);
                redisson.getTopic(EVENT_TOPIC, StringCodec.INSTANCE).publish(ns + ":" + id + ":" + untilMs);
            } catch (Exception e) {
                log.debug("熔断态下沉/广播失败（降级纯本地·不影响主链）: {}", e.toString());
            }
        });
    }

    /**
     * warm-start / 周期重同步：读某 ns 当前<b>未过期</b>熔断戳（供管理器初始化或周期愈合本地镜像），
     * 顺带清理 RMap 中已过期项防膨胀。Redis 不可用时返回空（降级纯本地）。
     */
    public Map<Long, Long> snapshotBans(String ns, long now) {
        Map<Long, Long> out = new HashMap<>();
        try {
            RMap<Long, Long> map = redisson.getMap(BAN_MAP_PREFIX + ns, LongCodec.INSTANCE);
            map.readAllMap().forEach((id, until) -> {
                if (until != null && until > now) {
                    out.put(id, until);
                } else if (until != null) {
                    map.remove(id);   // 过期项顺带清理
                }
            });
        } catch (Exception e) {
            log.debug("warm-start 读熔断态失败（降级纯本地）: {}", e.toString());
        }
        return out;
    }

    private void dispatch(String msg) {
        try {
            int i1 = msg.indexOf(':');
            int i2 = msg.lastIndexOf(':');
            if (i1 <= 0 || i2 <= i1) {
                return;
            }
            String ns = msg.substring(0, i1);
            long id = Long.parseLong(msg.substring(i1 + 1, i2));
            long until = Long.parseLong(msg.substring(i2 + 1));
            for (BanListener l : listeners) {
                l.onRemoteBan(ns, id, until);
            }
        } catch (Exception ignore) {
            // 脏消息不阻断订阅
        }
    }
}
