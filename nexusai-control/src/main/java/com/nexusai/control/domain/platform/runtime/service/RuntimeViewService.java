package com.nexusai.control.domain.platform.runtime.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexusai.limit.ConcurrencySlot;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import com.nexusai.router.health.ChannelHealthManager;
import com.nexusai.router.health.KeyHealthManager;
import com.nexusai.router.keypool.KeyPoolScorer;
import com.nexusai.router.health.RoutingHealthStat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时观测域服务：组装渠道密钥四层选号的运行时态快照（Key/渠道 健康熔断态 + 打分 + 并发槽水位）+ 一键复活（清熔断态）。
 * <p>纯阻塞逻辑（控制器经 boundedElastic 包裹）；从 {@code RuntimeAdminController} 下沉。运行时态在内存（单实例·多实例下沉 Redis 留后续）。
 * <p>control-domain→gateway 单向：注入 router（{@link KeyHealthManager}/{@link ChannelHealthManager}/{@link KeyPoolScorer}）+ limit（{@link ConcurrencySlot}）只读运行时态 + reset 复活。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class RuntimeViewService {

    private final NaiChannelKeyMapper channelKeyMapper;
    private final NaiChannelMapper channelMapper;
    private final KeyHealthManager keyHealth;
    private final ChannelHealthManager channelHealth;
    private final KeyPoolScorer scorer;
    private final ConcurrencySlot slot;

    /** Key 级运行时快照（健康分/EWMA 时延/学习 RPM/成败计数/可调度/打分/并发占用）。 */
    public List<Map<String, Object>> keySnapshots(Long channelId) {
        long now = System.currentTimeMillis();
        // 仅取快照所需列：不加载 credential（AES-GCM 密文）等无关字段（省内存·避免密文驻留）
        List<NaiChannelKey> rows = channelKeyMapper.selectList(
                Wrappers.lambdaQuery(NaiChannelKey.class)
                        .select(NaiChannelKey::getId, NaiChannelKey::getChannelId, NaiChannelKey::getWeight)
                        .eq(channelId != null, NaiChannelKey::getChannelId, channelId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (NaiChannelKey k : rows) {
            RoutingHealthStat st = keyHealth.peek(k.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyId", k.getId());
            m.put("channelId", k.getChannelId());
            m.put("weight", k.getWeight());
            m.put("healthScore", st == null ? 100 : st.healthScore());
            m.put("latencyEwmaMs", st == null ? 0 : Math.round(st.latencyEwmaMs()));
            m.put("rpmLearned", st == null ? 0 : st.rpmLearned());
            m.put("successCount", st == null ? 0 : st.successCount());
            m.put("errorCount", st == null ? 0 : st.errorCount());
            m.put("schedulable", st == null || st.schedulable(now));
            m.put("score", st == null ? null : Math.round(scorer.score(k, st) * 1000) / 1000.0);
            m.put("occupied", slot.occupied(k.getId()));
            out.add(m);
        }
        return out;
    }

    /** 渠道级运行时快照（健康分/EWMA 时延/成败计数/可调度）。 */
    public List<Map<String, Object>> channelSnapshots() {
        long now = System.currentTimeMillis();
        // 仅取快照所需列（id/name/protocol），不加载 paramOverride/headerOverride 等大字段
        List<NaiChannel> rows = channelMapper.selectList(
                Wrappers.lambdaQuery(NaiChannel.class)
                        .select(NaiChannel::getId, NaiChannel::getName, NaiChannel::getProtocol));
        List<Map<String, Object>> out = new ArrayList<>();
        for (NaiChannel c : rows) {
            RoutingHealthStat st = channelHealth.peek(c.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channelId", c.getId());
            m.put("name", c.getName());
            m.put("protocol", c.getProtocol());
            m.put("healthScore", st == null ? 100 : st.healthScore());
            m.put("latencyEwmaMs", st == null ? 0 : Math.round(st.latencyEwmaMs()));
            m.put("successCount", st == null ? 0 : st.successCount());
            m.put("errorCount", st == null ? 0 : st.errorCount());
            m.put("schedulable", st == null || st.schedulable(now));
            out.add(m);
        }
        return out;
    }
}
