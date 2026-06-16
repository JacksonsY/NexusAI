package com.nexusai.control.domain.routing.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.model.PageResult;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.routing.model.vo.ChannelAssignResultVo;
import com.nexusai.control.domain.routing.model.vo.ModelChannelLinkVo;
import com.nexusai.control.domain.routing.model.vo.ModelRowVo;
import com.nexusai.persistence.log.RequestLogStore;
import com.nexusai.persistence.log.stat.HealthStat;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.mapper.NaiChannelMapper;
import com.nexusai.persistence.mapper.NaiModelMapper;
import com.nexusai.persistence.po.NaiAbility;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 模型目录汇总（模型管理页）：
 * 列表页三统计一次拼齐——关联渠道数 active/total（nai_ability×渠道状态）+ 近 30 天调用次数
 * （读时聚合 nai_request_log·不用写时计数器）；缺失模型检测=路由表登记了但目录没建价 → 计费口径缺口。
 * <p>聚合全走批量查询（ability/channel 全量小表 + 日志 store 一条维度聚合），杜绝 N+1。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelSummaryService {

    /** 调用次数统计窗口（读时聚合·与健康监控同源口径） */
    private static final int USAGE_WINDOW_DAYS = 30;
    private static final String DEFAULT_GROUP = "default";
    private static final String DEFAULT_MODALITY = "CHAT";
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;

    private final NaiModelMapper modelMapper;
    private final NaiAbilityMapper abilityMapper;
    private final NaiChannelMapper channelMapper;
    private final RequestLogStore logStore;

    /** 模型分页列表（每行 = 模型 + 渠道计数 + 30 天调用次数）。 */
    public PageResult<ModelRowVo> page(long pageNum, long pageSize, String search, Integer status) {
        LambdaQueryWrapper<NaiModel> wrapper = new LambdaQueryWrapper<>();
        if (search != null && !search.isBlank()) {
            String kw = search.trim();
            wrapper.and(w -> w.like(NaiModel::getModelKey, kw).or().like(NaiModel::getModelAlias, kw));
        }
        if (status != null) {
            wrapper.eq(NaiModel::getStatus, status);
        }
        wrapper.orderByDesc(NaiModel::getId);
        Page<NaiModel> page = modelMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);

        Map<String, long[]> channelCounts = channelCountsByModel();
        Map<String, HealthStat> usage = usageByModel();

        List<ModelRowVo> rows = new ArrayList<>(page.getRecords().size());
        for (NaiModel model : page.getRecords()) {
            rows.add(toRow(model, channelCounts, usage));
        }
        return PageResult.of(rows, page.getTotal(), pageNum, pageSize);
    }

    /** 单模型详情（含统计·详情抽屉头部/基本信息 Tab）。 */
    public ModelRowVo detail(Long id) {
        NaiModel model = modelMapper.selectById(id);
        if (model == null) {
            return null;
        }
        return toRow(model, channelCountsByModel(), usageByModel());
    }

    /**
     * 缺失模型检测：nai_ability 已登记路由但 nai_model 没建元数据的模型名。
     * 这些模型能被调度转发但无费率口径 → 计费缺口，必须在目录页顶部曝光。
     */
    public List<String> missingModels() {
        Set<String> routed = new TreeSet<>();
        for (NaiAbility ability : abilityMapper.selectList(
                new QueryWrapper<NaiAbility>().select("DISTINCT model"))) {
            if (ability.getModel() != null) {
                routed.add(ability.getModel());
            }
        }
        for (NaiModel model : modelMapper.selectList(
                new LambdaQueryWrapper<NaiModel>().select(NaiModel::getModelKey))) {
            routed.remove(model.getModelKey());
        }
        return new ArrayList<>(routed);
    }

    /** 模型的关联渠道列表（详情抽屉·含路由行配置 + 渠道出站映射后的目标模型名）。 */
    public List<ModelChannelLinkVo> channelsOf(String modelKey) {
        List<NaiAbility> abilities = abilityMapper.selectList(
                new LambdaQueryWrapper<NaiAbility>().eq(NaiAbility::getModel, modelKey));
        Map<Long, NaiChannel> channels = channelById();
        List<ModelChannelLinkVo> out = new ArrayList<>(abilities.size());
        for (NaiAbility ability : abilities) {
            NaiChannel channel = channels.get(ability.getChannelId());
            out.add(new ModelChannelLinkVo(
                    ability.getChannelId(),
                    channel == null ? "渠道#" + ability.getChannelId() : channel.getName(),
                    channel == null ? null : channel.getProtocol(),
                    channel == null ? null : channel.getBaseUrl(),
                    channel == null ? STATUS_DISABLED : channel.getStatus(),
                    Boolean.TRUE.equals(ability.getEnabled()),
                    ability.getPriority(),
                    ability.getWeight(),
                    resolveTargetModel(channel, modelKey)));
        }
        return out;
    }

    /**
     * 声明式渠道关联对账（批量分配到提供商的全量版）：
     * 选中渠道集合即目标态——缺的补 ability 行（默认 group·priority/weight 取渠道现值或默认），多的删。
     * 返回 {added, removed}。
     */
    public ChannelAssignResultVo assignChannels(String modelKey, List<Long> channelIds) {
        NaiModel model = modelMapper.selectOne(
                new LambdaQueryWrapper<NaiModel>().eq(NaiModel::getModelKey, modelKey));
        Set<Long> target = channelIds == null ? Set.of() : new HashSet<>(channelIds);

        Set<Long> existing = new HashSet<>();
        for (NaiAbility ability : abilityMapper.selectList(
                new LambdaQueryWrapper<NaiAbility>().eq(NaiAbility::getModel, modelKey))) {
            existing.add(ability.getChannelId());
        }

        int added = 0;
        for (Long channelId : target) {
            if (existing.contains(channelId)) {
                continue;
            }
            NaiAbility ability = new NaiAbility();
            ability.setGroupCode(DEFAULT_GROUP);
            ability.setModel(modelKey);
            ability.setChannelId(channelId);
            ability.setPriority(0);
            ability.setWeight(1);
            ability.setEnabled(true);
            ability.setModality(model == null || model.getModality() == null
                    ? DEFAULT_MODALITY : model.getModality());
            abilityMapper.insert(ability);
            added++;
        }

        List<Long> toRemove = existing.stream().filter(id -> !target.contains(id)).toList();
        int removed = 0;
        if (!toRemove.isEmpty()) {
            removed = abilityMapper.delete(new LambdaQueryWrapper<NaiAbility>()
                    .eq(NaiAbility::getModel, modelKey)
                    .in(NaiAbility::getChannelId, toRemove));
        }
        return new ChannelAssignResultVo(added, removed);
    }

    /** 批量删除模型（批量管理对话框）。 */
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return modelMapper.deleteByIds(ids);
    }

    // ===== 聚合底座 =====

    private ModelRowVo toRow(NaiModel model, Map<String, long[]> channelCounts,
                             Map<String, HealthStat> usage) {
        long[] counts = channelCounts.getOrDefault(model.getModelKey(), new long[2]);
        HealthStat stat = usage.get(model.getModelKey());
        return new ModelRowVo(
                model,
                counts[0],
                counts[1],
                stat == null ? 0 : stat.total(),
                stat == null ? null : stat.lastEventAt());
    }

    /** 模型 → [关联渠道总数, 活跃数]（活跃 = ability.enabled 且渠道启用）。 */
    private Map<String, long[]> channelCountsByModel() {
        Map<Long, Integer> channelStatus = new HashMap<>();
        for (NaiChannel channel : channelMapper.selectList(
                new QueryWrapper<NaiChannel>().select("id", "status"))) {
            channelStatus.put(channel.getId(), channel.getStatus() == null ? STATUS_DISABLED : channel.getStatus());
        }
        Map<String, Set<Long>> total = new HashMap<>();
        Map<String, Set<Long>> active = new HashMap<>();
        for (NaiAbility ability : abilityMapper.selectList(new QueryWrapper<NaiAbility>()
                .select("model", "channel_id", "enabled"))) {
            total.computeIfAbsent(ability.getModel(), k -> new HashSet<>()).add(ability.getChannelId());
            boolean channelEnabled = channelStatus.getOrDefault(ability.getChannelId(), STATUS_DISABLED) == STATUS_ENABLED;
            if (Boolean.TRUE.equals(ability.getEnabled()) && channelEnabled) {
                active.computeIfAbsent(ability.getModel(), k -> new HashSet<>()).add(ability.getChannelId());
            }
        }
        Map<String, long[]> out = new HashMap<>();
        total.forEach((model, ids) -> out.put(model,
                new long[]{ids.size(), active.getOrDefault(model, Set.of()).size()}));
        return out;
    }

    /** 模型 → 近 30 天调用统计（日志 store 一条维度聚合）。 */
    private Map<String, HealthStat> usageByModel() {
        Map<String, HealthStat> usage = new HashMap<>();
        for (HealthStat stat : logStore.healthByDimension("model", null,
                LocalDateTime.now().minusDays(USAGE_WINDOW_DAYS))) {
            usage.put(stat.key(), stat);
        }
        return usage;
    }

    private Map<Long, NaiChannel> channelById() {
        Map<Long, NaiChannel> map = new HashMap<>();
        for (NaiChannel channel : channelMapper.selectList(null)) {
            map.put(channel.getId(), channel);
        }
        return map;
    }

    /** 渠道出站 model_mapping 解析（与 RouteService.resolveModel 同口径·展示用）。 */
    private String resolveTargetModel(NaiChannel channel, String model) {
        if (channel == null || channel.getModelMapping() == null || channel.getModelMapping().isBlank()) {
            return model;
        }
        try {
            Map<String, String> mapping = JsonUtils.parse(channel.getModelMapping(),
                    new TypeReference<Map<String, String>>() {
                    });
            return mapping.getOrDefault(model, model);
        } catch (Exception e) {
            // 渠道 model_mapping 配置非法 JSON：展示口径降级回原模型名（不阻断列表/详情渲染）
            log.debug("渠道 {} model_mapping 解析失败，目标模型回退为原名: {}",
                    channel.getId(), e.getMessage());
            return model;
        }
    }
}
