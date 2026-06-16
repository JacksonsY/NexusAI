package com.nexusai.control.domain.routing.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nexusai.control.domain.routing.channel.vo.ChannelSummaryVo;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.HealthStat;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import com.nexusai.router.health.ChannelHealthManager;
import com.nexusai.router.health.KeyHealthManager;
import com.nexusai.router.health.RoutingHealthStat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道汇总（提供商页）：一次查询拉齐列表页全部聚合——
 * 渠道配置 + 凭证水位（启用/总数·可调度数）+ 注册模型数 + 近 24h 流量（请求/成功率）+ 运行时健康分。
 * <p>聚合全走批量查询（凭证/模型 group by channel_id，流量走日志 store 维度聚合），杜绝 N+1。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class ChannelSummaryService {

    /** 近 24h 流量统计窗口（小时） */
    private static final int TRAFFIC_WINDOW_HOURS = 24;
    /** 无运行时健康快照时的默认健康分（满分） */
    private static final int DEFAULT_HEALTH_SCORE = 100;
    /** 启用状态值 */
    private static final int STATUS_ENABLED = 1;
    /** 凭证指纹：短于此长度直接全掩码·保留尾部位数 */
    private static final int MASK_MIN_LENGTH = 8;
    private static final int MASK_SUFFIX_LENGTH = 4;
    private static final String MASK_PREFIX = "***";

    private final NaiChannelMapper channelMapper;
    private final NaiChannelKeyMapper channelKeyMapper;
    private final NaiAbilityMapper abilityMapper;
    private final RequestLogStore logStore;
    private final ChannelHealthManager channelHealth;
    private final KeyHealthManager keyHealth;

    /** 渠道汇总列表（每渠道一行·含配置 + 水位 + 流量 + 健康）。 */
    public List<ChannelSummaryVo> summaries() {
        LocalDateTime now = LocalDateTime.now();
        long nowMillis = System.currentTimeMillis();
        List<NaiChannel> channels = channelMapper.selectList(null);

        // 凭证水位（总数/启用·一条 group by）+ 可调度数（运行时熔断判定逐 Key）+ 首条指纹（详情展示）
        Map<Long, long[]> keyCounts = new HashMap<>();
        Map<Long, Long> schedulableKeys = new HashMap<>();
        Map<Long, String> maskedCredentials = new HashMap<>();
        for (NaiChannelKey key : channelKeyMapper.selectList(
                new QueryWrapper<NaiChannelKey>().orderByAsc("id"))) {
            long[] acc = keyCounts.computeIfAbsent(key.getChannelId(), k -> new long[2]);
            acc[0]++;
            maskedCredentials.putIfAbsent(key.getChannelId(), mask(key.getCredential()));
            boolean enabled = key.getStatus() != null && key.getStatus() == STATUS_ENABLED;
            if (enabled) {
                acc[1]++;
                if (keyHealth.schedulable(key.getId(), nowMillis)) {
                    schedulableKeys.merge(key.getChannelId(), 1L, Long::sum);
                }
            }
        }

        // 注册模型数（路由表按渠道计·启用/总数）
        Map<Long, long[]> modelCounts = new HashMap<>();
        for (NaiAbility ability : abilityMapper.selectList(new QueryWrapper<NaiAbility>()
                .select("channel_id", "enabled", "model"))) {
            long[] acc = modelCounts.computeIfAbsent(ability.getChannelId(), k -> new long[2]);
            acc[0]++;
            if (Boolean.TRUE.equals(ability.getEnabled())) {
                acc[1]++;
            }
        }

        // 近 24h 流量（请求/成功·日志 store 一条维度聚合）
        Map<String, HealthStat> traffic = new HashMap<>();
        for (HealthStat stat : logStore.healthByDimension("channel_id", null, now.minusHours(TRAFFIC_WINDOW_HOURS))) {
            traffic.put(stat.key(), stat);
        }

        List<ChannelSummaryVo> out = new ArrayList<>(channels.size());
        for (NaiChannel ch : channels) {
            long[] keys = keyCounts.getOrDefault(ch.getId(), new long[2]);
            long[] models = modelCounts.getOrDefault(ch.getId(), new long[2]);

            HealthStat stat = traffic.get(String.valueOf(ch.getId()));
            long requests24h = stat == null ? 0 : stat.total();
            Double successRate24h = stat == null || stat.total() == 0
                    ? null : (double) stat.success() / stat.total();
            LocalDateTime lastEventAt = stat == null ? null : stat.lastEventAt();

            RoutingHealthStat channelStat = channelHealth.peek(ch.getId());
            int healthScore = channelStat == null ? DEFAULT_HEALTH_SCORE : channelStat.healthScore();

            out.add(new ChannelSummaryVo(
                    ch,
                    keys[0],
                    keys[1],
                    schedulableKeys.getOrDefault(ch.getId(), 0L),
                    maskedCredentials.get(ch.getId()),
                    models[0],
                    models[1],
                    requests24h,
                    successRate24h,
                    lastEventAt,
                    healthScore,
                    channelHealth.schedulable(ch.getId(), nowMillis)));
        }
        return out;
    }

    /** 凭证指纹（与 CredentialAdminService 同口径：仅回尾 4 位）。 */
    private static String mask(String cipher) {
        if (cipher == null || cipher.length() < MASK_MIN_LENGTH) {
            return MASK_PREFIX;
        }
        return MASK_PREFIX + cipher.substring(cipher.length() - MASK_SUFFIX_LENGTH);
    }
}
