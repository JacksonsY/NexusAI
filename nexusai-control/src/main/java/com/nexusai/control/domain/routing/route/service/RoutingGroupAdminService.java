package com.nexusai.control.domain.routing.route.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.control.domain.routing.route.vo.RoutingPolicyVo;
import com.nexusai.persistence.mapper.NaiRoutingGroupMapper;
import com.nexusai.persistence.po.NaiRoutingGroup;
import com.nexusai.router.config.SchedulingSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度策略分组管理：增删改查 + 设系统默认（全局唯一·事务内先清后置）。
 * 写后失效 {@link SchedulingSettings} 本地快照即时生效。
 *
 * @author nexusai
 */
@Service
@RequiredArgsConstructor
public class RoutingGroupAdminService {

    private final NaiRoutingGroupMapper mapper;
    private final SchedulingSettings schedulingSettings;

    public List<NaiRoutingGroup> list() {
        return mapper.selectList(new LambdaQueryWrapper<NaiRoutingGroup>()
                .orderByDesc(NaiRoutingGroup::getIsSystemDefault)
                .orderByAsc(NaiRoutingGroup::getId));
    }

    @Transactional
    public Long create(NaiRoutingGroup body) {
        NaiRoutingGroup group = new NaiRoutingGroup();
        group.setName(body.getName());
        group.setDescription(body.getDescription());
        group.setEnabled(body.getEnabled() == null || body.getEnabled());
        group.setIsSystemDefault(Boolean.TRUE.equals(body.getIsSystemDefault()));
        group.setConfigJson(normalizeConfig(body.getConfigJson()));
        group.setVersion(1);
        if (group.getIsSystemDefault()) {
            clearSystemDefault();
        }
        mapper.insert(group);
        schedulingSettings.invalidate();
        return group.getId();
    }

    @Transactional
    public void update(Long id, NaiRoutingGroup body) {
        NaiRoutingGroup existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        if (Boolean.TRUE.equals(body.getIsSystemDefault()) && !Boolean.TRUE.equals(existing.getIsSystemDefault())) {
            clearSystemDefault();
        }
        NaiRoutingGroup patch = new NaiRoutingGroup();
        patch.setId(id);
        patch.setName(body.getName());
        patch.setDescription(body.getDescription());
        patch.setEnabled(body.getEnabled());
        patch.setIsSystemDefault(body.getIsSystemDefault());
        if (body.getConfigJson() != null) {
            patch.setConfigJson(normalizeConfig(body.getConfigJson()));
        }
        patch.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
        mapper.updateById(patch);
        schedulingSettings.invalidate();
    }

    @Transactional
    public void delete(Long id) {
        mapper.deleteById(id);
        schedulingSettings.invalidate();
    }

    /** 设为系统默认（全局唯一）。 */
    @Transactional
    public void setSystemDefault(Long id) {
        NaiRoutingGroup existing = mapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("策略不存在: " + id);
        }
        clearSystemDefault();
        NaiRoutingGroup patch = new NaiRoutingGroup();
        patch.setId(id);
        patch.setIsSystemDefault(true);
        mapper.updateById(patch);
        schedulingSettings.invalidate();
    }

    /**
     * 读系统默认策略生效的 defaultPolicy（兼容运行时口径：优先级管理对话框/模型路由 Tab 在用）。
     */
    public RoutingPolicyVo effectiveDefaultPolicy() {
        SchedulingSettings.Policy policy = schedulingSettings.resolve(null);
        return new RoutingPolicyVo(policy.schedulingMode(), policy.priorityMode());
    }

    /**
     * 更新系统默认策略的 defaultPolicy（旧 PUT /admin/routing/scheduling 语义保持·存储迁移到策略表）；
     * 无系统默认策略时自动创建一条「系统默认策略」。
     */
    @Transactional
    public void updateDefaultPolicy(String schedulingMode, String priorityMode) {
        NaiRoutingGroup group = mapper.selectOne(new LambdaQueryWrapper<NaiRoutingGroup>()
                .eq(NaiRoutingGroup::getIsSystemDefault, true)
                .last("limit 1"));
        Map<String, Object> config = group == null || group.getConfigJson() == null || group.getConfigJson().isBlank()
                ? new LinkedHashMap<>()
                : JsonUtils.parse(group.getConfigJson(), new TypeReference<Map<String, Object>>() {
                });
        Map<String, Object> defaultPolicy = config.get("defaultPolicy") instanceof Map<?, ?> m
                ? new LinkedHashMap<>(asStringMap(m))
                : new LinkedHashMap<>();
        if (SchedulingSettings.isSchedulingMode(schedulingMode)) {
            defaultPolicy.put("schedulingMode", schedulingMode);
        }
        if (SchedulingSettings.isPriorityMode(priorityMode)) {
            defaultPolicy.put("priorityMode", priorityMode);
        }
        config.put("defaultPolicy", defaultPolicy);
        config.putIfAbsent("modelPolicies", List.of());

        if (group == null) {
            NaiRoutingGroup created = new NaiRoutingGroup();
            created.setName("系统默认策略");
            created.setDescription("由调度配置自动创建（统一调度）");
            created.setEnabled(true);
            created.setIsSystemDefault(true);
            created.setConfigJson(JsonUtils.toJson(config));
            created.setVersion(1);
            mapper.insert(created);
        } else {
            NaiRoutingGroup patch = new NaiRoutingGroup();
            patch.setId(group.getId());
            patch.setConfigJson(JsonUtils.toJson(config));
            patch.setVersion((group.getVersion() == null ? 1 : group.getVersion()) + 1);
            mapper.updateById(patch);
        }
        schedulingSettings.invalidate();
    }

    private void clearSystemDefault() {
        mapper.update(null, new LambdaUpdateWrapper<NaiRoutingGroup>()
                .eq(NaiRoutingGroup::getIsSystemDefault, true)
                .set(NaiRoutingGroup::getIsSystemDefault, false));
    }

    /** 校验并规整 configJson（非法 JSON / 非法枚举直接拒绝·防脏配置进运行时）。 */
    private static String normalizeConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("defaultPolicy", Map.of(
                    "priorityMode", SchedulingSettings.PRIORITY_PROVIDER,
                    "schedulingMode", SchedulingSettings.MODE_CACHE_AFFINITY));
            empty.put("modelPolicies", List.of());
            return JsonUtils.toJson(empty);
        }
        Map<String, Object> config = JsonUtils.parse(configJson, new TypeReference<Map<String, Object>>() {
        });
        if (config.get("defaultPolicy") instanceof Map<?, ?> dp) {
            Map<String, Object> typed = asStringMap(dp);
            Object pm = typed.get("priorityMode");
            Object sm = typed.get("schedulingMode");
            if (pm != null && !SchedulingSettings.isPriorityMode(String.valueOf(pm))) {
                throw new IllegalArgumentException("非法 priorityMode: " + pm);
            }
            if (sm != null && !SchedulingSettings.isSchedulingMode(String.valueOf(sm))) {
                throw new IllegalArgumentException("非法 schedulingMode: " + sm);
            }
        }
        if (config.get("modelPolicies") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> mp) {
                    Map<String, Object> typed = asStringMap(mp);
                    Object pm = typed.get("priorityMode");
                    Object sm = typed.get("schedulingMode");
                    if (pm != null && !SchedulingSettings.isPriorityMode(String.valueOf(pm))) {
                        throw new IllegalArgumentException("非法 priorityMode: " + pm);
                    }
                    if (sm != null && !SchedulingSettings.isSchedulingMode(String.valueOf(sm))) {
                        throw new IllegalArgumentException("非法 schedulingMode: " + sm);
                    }
                }
            }
        }
        return JsonUtils.toJson(config);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
