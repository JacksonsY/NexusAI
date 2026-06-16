package com.nexusai.router.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.common.event.ConfigChangedEvent;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.persistence.mapper.NaiModelMapper;
import com.nexusai.persistence.po.NaiModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 入站模型别名归一：把请求里的模型名按 nai_model.alias_patterns
 * （JSON 正则数组·完整匹配）归一到目录 modelKey，让计费费率/路由 ability/日志统计全部对齐同一口径——
 * 典型场景 {@code claude-3-5-sonnet-20241022 → claude-3-5-sonnet}。
 * <p>同时承担模型停用闸门：归一/精确命中的模型 {@code status=0} → 403 {@link GatewayErrorCode#MODEL_DISABLED}。
 * <p>热路径成本：精确命中（多数请求）仅一次 map 查找；miss 才进正则链。别名目录 30s TTL +
 * {@link ConfigChangedEvent} 即时失效（与 {@link com.nexusai.router.cache.RouteCache} 同节奏）。
 * 目录未登记的模型名原样放行（missing-model 场景由模型管理页曝光，不在数据面拦截）。
 *
 * @author nexusai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelAliasResolver {

    /** 每模型别名规则上限 */
    public static final int MAX_PATTERNS_PER_MODEL = 10;
    private static final long REFRESH_MS = 30_000;

    private final NaiModelMapper modelMapper;

    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of());
    private final AtomicLong loadedAt = new AtomicLong();

    private record Rule(Pattern pattern, String modelKey) {
    }

    private record Snapshot(Map<String, NaiModel> byKey, List<Rule> rules) {
    }

    /** 归一模型名（命中停用模型抛 403；未登记原样返回）。 */
    public String resolve(String model) {
        if (model == null || model.isBlank()) {
            return model;
        }
        Snapshot snap = current();
        NaiModel exact = snap.byKey().get(model);
        if (exact != null) {
            ensureEnabled(exact);
            return model;
        }
        for (Rule rule : snap.rules()) {
            if (rule.pattern().matcher(model).matches()) {
                NaiModel target = snap.byKey().get(rule.modelKey());
                if (target != null) {
                    ensureEnabled(target);
                }
                return rule.modelKey();
            }
        }
        return model;
    }

    private static void ensureEnabled(NaiModel model) {
        if (model.getStatus() != null && model.getStatus() == 0) {
            throw GatewayException.of(GatewayErrorCode.MODEL_DISABLED, "模型已停用: " + model.getModelKey());
        }
    }

    private Snapshot current() {
        long last = loadedAt.get();
        long now = System.currentTimeMillis();
        if (now - last >= REFRESH_MS && loadedAt.compareAndSet(last, now)) {
            try {
                reload();
            } catch (Exception e) {
                log.warn("模型别名快照加载失败，沿用旧快照: {}", e.getMessage());
            }
        }
        return snapshot;
    }

    private void reload() {
        List<NaiModel> models = modelMapper.selectList(null);
        Map<String, NaiModel> byKey = new HashMap<>(models.size() * 2);
        List<Rule> rules = new ArrayList<>();
        for (NaiModel model : models) {
            byKey.put(model.getModelKey(), model);
            String json = model.getAliasPatterns();
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                List<String> patterns = JsonUtils.parse(json, new TypeReference<List<String>>() {
                });
                int count = 0;
                for (String raw : patterns) {
                    if (raw == null || raw.isBlank() || ++count > MAX_PATTERNS_PER_MODEL) {
                        continue;
                    }
                    try {
                        rules.add(new Rule(Pattern.compile(raw.trim()), model.getModelKey()));
                    } catch (Exception bad) {
                        log.warn("模型 {} 别名正则非法已跳过: {}", model.getModelKey(), raw);
                    }
                }
            } catch (Exception e) {
                log.warn("模型 {} alias_patterns 解析失败已跳过", model.getModelKey());
            }
        }
        this.snapshot = new Snapshot(byKey, rules);
    }

    /** 配置变更 → 立即重载（下一请求生效）。 */
    @EventListener
    public void onConfigChanged(ConfigChangedEvent event) {
        loadedAt.set(0);
    }

    /** 快照规则只读视图（控制台「缓存监控·模型别名缓存」）。 */
    public record AliasRuleView(String pattern, String modelKey, String modelAlias, Integer status) {
    }

    public record SnapshotView(int modelCount, int ruleCount, long loadedAt, long refreshMs,
                               List<AliasRuleView> rules) {
    }

    public SnapshotView snapshotView() {
        Snapshot snap = current();
        List<AliasRuleView> views = new ArrayList<>(snap.rules().size());
        for (Rule rule : snap.rules()) {
            NaiModel target = snap.byKey().get(rule.modelKey());
            views.add(new AliasRuleView(rule.pattern().pattern(), rule.modelKey(),
                    target == null ? null : target.getModelAlias(),
                    target == null ? null : target.getStatus()));
        }
        return new SnapshotView(snap.byKey().size(), views.size(), loadedAt.get(), REFRESH_MS, views);
    }

    /** 控制台手动刷新：清时间戳 → 立即重载新快照。 */
    public void forceRefresh() {
        loadedAt.set(System.currentTimeMillis());
        reload();
    }
}
