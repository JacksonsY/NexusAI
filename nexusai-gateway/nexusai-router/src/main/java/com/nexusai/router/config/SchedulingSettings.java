package com.nexusai.router.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.mapper.NaiRoutingGroupMapper;
import com.nexusai.persistence.po.NaiRoutingGroup;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度策略解析（调度策略页驱动）：从 {@code nai_routing_group} 的
 * <b>系统默认且启用</b>的策略解析"优先级模式 × 调度策略"，支持 per-model 覆盖（modelPolicies）。
 * <ul>
 *   <li><b>schedulingMode</b>：{@code cache_affinity}（同会话粘渠道·利用上游 Prompt Cache）/
 *       {@code load_balance}（同优先级组内加权随机）/ {@code fixed_order}（严格按序）；</li>
 *   <li><b>priorityMode</b>：{@code provider}（第一级按渠道 priority·默认）/
 *       {@code key}（第一级按路由表 priority·Key priority 在第二级选号打分生效）。</li>
 * </ul>
 * 热路径读本地快照（30s 刷新·DB 真理源·多实例最迟 30s 收敛）；admin 写策略后调 {@link #invalidate()} 本实例即时生效。
 * 无系统默认策略时回退内置默认（cache_affinity / provider）。
 *
 * @author nexusai
 */
@Component
public class SchedulingSettings {

    public static final String MODE_CACHE_AFFINITY = "cache_affinity";
    public static final String MODE_LOAD_BALANCE = "load_balance";
    public static final String MODE_FIXED_ORDER = "fixed_order";

    public static final String PRIORITY_PROVIDER = "provider";
    public static final String PRIORITY_KEY = "key";

    private static final long REFRESH_MS = 30_000;

    /** 解析后的策略（优先级模式 + 调度策略）。 */
    public record Policy(String priorityMode, String schedulingMode) {
        public static final Policy BUILTIN_DEFAULT = new Policy(PRIORITY_PROVIDER, MODE_CACHE_AFFINITY);
    }

    private final NaiRoutingGroupMapper routingGroupMapper;
    private final AtomicLong loadedAt = new AtomicLong();
    private volatile Policy defaultPolicy = Policy.BUILTIN_DEFAULT;
    private volatile Map<String, Policy> modelPolicies = Map.of();

    public SchedulingSettings(NaiRoutingGroupMapper routingGroupMapper) {
        this.routingGroupMapper = routingGroupMapper;
    }

    /** 解析指定模型生效的策略（modelPolicies 命中则覆盖 defaultPolicy）。 */
    public Policy resolve(String model) {
        refreshIfStale();
        Policy p = model == null ? null : modelPolicies.get(model);
        return p != null ? p : defaultPolicy;
    }

    /** 当前默认调度策略（无模型上下文场景）。 */
    public String schedulingMode() {
        refreshIfStale();
        return defaultPolicy.schedulingMode();
    }

    /** 当前默认优先级生效层（无模型上下文场景）。 */
    public String priorityMode() {
        refreshIfStale();
        return defaultPolicy.priorityMode();
    }

    /** admin 写策略后失效本地快照（本实例即时生效·其余实例 ≤30s 轮询跟进）。 */
    public void invalidate() {
        loadedAt.set(0);
        refreshIfStale();
    }

    private void refreshIfStale() {
        long last = loadedAt.get();
        long now = System.currentTimeMillis();
        if (now - last < REFRESH_MS || !loadedAt.compareAndSet(last, now)) {
            return;
        }
        try {
            NaiRoutingGroup group = routingGroupMapper.selectOne(new LambdaQueryWrapper<NaiRoutingGroup>()
                    .eq(NaiRoutingGroup::getIsSystemDefault, true)
                    .eq(NaiRoutingGroup::getEnabled, true)
                    .last("limit 1"));
            apply(group);
        } catch (Exception ignored) {
            // DB 暂不可达时沿用本地快照（配置面弱依赖·不阻塞选路）
        }
    }

    private void apply(NaiRoutingGroup group) {
        if (group == null || group.getConfigJson() == null || group.getConfigJson().isBlank()) {
            this.defaultPolicy = Policy.BUILTIN_DEFAULT;
            this.modelPolicies = Map.of();
            return;
        }
        Map<String, Object> config = JsonUtils.parse(group.getConfigJson(), new TypeReference<Map<String, Object>>() {
        });
        this.defaultPolicy = toPolicy(asMap(config.get("defaultPolicy")), Policy.BUILTIN_DEFAULT);
        Map<String, Policy> models = new HashMap<>();
        if (config.get("modelPolicies") instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> mp = asMap(item);
                Object model = mp.get("model");
                if (model instanceof String m && !m.isBlank()) {
                    models.put(m, toPolicy(mp, this.defaultPolicy));
                }
            }
        }
        this.modelPolicies = Map.copyOf(models);
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private static Policy toPolicy(Map<String, Object> raw, Policy fallback) {
        String priority = raw.get("priorityMode") instanceof String s && isPriorityMode(s)
                ? s : fallback.priorityMode();
        String scheduling = raw.get("schedulingMode") instanceof String s && isSchedulingMode(s)
                ? s : fallback.schedulingMode();
        return new Policy(priority, scheduling);
    }

    public static boolean isSchedulingMode(String mode) {
        return MODE_CACHE_AFFINITY.equals(mode) || MODE_LOAD_BALANCE.equals(mode) || MODE_FIXED_ORDER.equals(mode);
    }

    public static boolean isPriorityMode(String mode) {
        return PRIORITY_PROVIDER.equals(mode) || PRIORITY_KEY.equals(mode);
    }
}
