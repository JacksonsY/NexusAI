package com.nexusai.control.domain.platform.module.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.util.JsonUtils;
import com.nexusai.runtime.ModuleMetricsRegistry;
import com.nexusai.runtime.ModuleToggles;
import com.nexusai.control.domain.platform.module.catalog.ModuleCatalog;
import com.nexusai.control.domain.platform.module.catalog.ModuleCatalog.ModuleDef;
import com.nexusai.control.domain.platform.module.catalog.ModuleCatalog.ParamDef;
import com.nexusai.control.domain.platform.system.service.SystemConfigService;
import com.nexusai.router.affinity.StickyAffinity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.nexusai.control.domain.platform.notify.service.NotificationService;
import com.nexusai.control.domain.platform.system.job.ConfigBackupJob;

/**
 * 功能模块读写编排：三态
 * available（装配条件满足）/ enabled（KV 开关·默认取装配默认）/ active（两者皆真）。
 * 模块清单与参数 schema 在 {@link ModuleCatalog}；数据面 gate 经 {@link ModuleToggles} 5s 热生效。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleService {

    /** 卡片排序配置键（拖拽排序） */
    public static final String ORDER_KEY = "module_management.order";

    private final ModuleCatalog catalog;
    private final SystemConfigService systemConfig;
    private final ModuleToggles toggles;
    private final StickyAffinity stickyAffinity;
    /** 数据面模块指标（relay 侧 PiiRedactor/DiskCacheStore 自注册·解除 control-domain→relay 类型依赖） */
    private final ModuleMetricsRegistry moduleMetrics;
    private final CredentialCipher cipher;
    private final ObjectProvider<NotificationService> notificationProvider;
    private final ObjectProvider<ConfigBackupJob> backupProvider;

    /** 模块设置（schema+当前值+运行指标）。 */
    public Map<String, Object> settings(String name) {
        ModuleDef def = catalog.require(name);
        List<Map<String, Object>> params = new ArrayList<>();
        for (ParamDef p : catalog.paramsOf(name)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.key());
            m.put("label", p.label());
            m.put("type", p.type());
            m.put("defaultValue", p.defaultValue());
            m.put("unit", p.unit());
            m.put("hint", p.hint());
            m.put("hot", p.hot());
            m.putAll(p.extra());
            Object current = p.hot() ? systemConfig.getParsed("module." + name + "." + p.key()) : null;
            if ("secret".equals(p.type())) {
                // 密文不回显：仅标记已配置（前端显示掩码占位）
                m.put("value", null);
                m.put("configured", current != null && !String.valueOf(current).isBlank());
            } else {
                m.put("value", current == null ? p.defaultValue() : current);
            }
            params.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("displayName", def.displayName());
        out.put("adminRoute", def.adminRoute());
        out.put("params", params);
        out.put("metrics", metricsOf(name));
        return out;
    }

    /** 保存模块参数（仅 schema 内 hot 参数·写 KV 5s 热生效）。 */
    public void putSettings(String name, Map<String, Object> values) {
        Map<String, ParamDef> schema = new LinkedHashMap<>();
        for (ParamDef p : catalog.paramsOf(name)) {
            if (p.hot()) {
                schema.put(p.key(), p);
            }
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            ParamDef p = schema.get(e.getKey());
            if (p == null) {
                throw new IllegalArgumentException("模块 " + name + " 无可写参数: " + e.getKey());
            }
            String cfgKey = "module." + name + "." + e.getKey();
            Object value = e.getValue();
            if ("secret".equals(p.type())) {
                String plain = value == null ? "" : String.valueOf(value);
                if (plain.isBlank()) {
                    systemConfig.delete(cfgKey);
                    continue;
                }
                value = cipher.encrypt(plain);
            }
            systemConfig.put(cfgKey, value, def(name, p));
        }
    }

    private static String def(String name, ParamDef p) {
        return "模块参数（" + name + "·" + p.label() + "）";
    }

    /** 模块动作（设置抽屉按钮）：通知服务=发送测试 / 配置备份=立即备份。 */
    public Map<String, Object> action(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (name) {
            case "important_notification" -> {
                NotificationService svc = notificationProvider.getIfAvailable();
                if (svc == null) {
                    throw new IllegalStateException("通知服务不可用");
                }
                List<Map<String, Object>> results = svc.sendTest("proxy_node_status", null);
                boolean anyOk = results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("success")));
                out.put("channels", results);
                out.put("message", anyOk ? "测试通知已发送" : "测试发送失败（无可用通道或通道异常）");
            }
            case "config_backup" -> {
                ConfigBackupJob job = backupProvider.getIfAvailable();
                Path file = job == null ? null : job.backupOnce();
                if (file == null) {
                    throw new IllegalStateException("备份失败（检查目录权限）");
                }
                out.put("message", "备份完成: " + file);
            }
            default -> throw new IllegalArgumentException("模块 " + name + " 无可执行动作");
        }
        return out;
    }

    /** 运行指标摘要（仅现成数据源·无则空列表）。 */
    private List<Map<String, Object>> metricsOf(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        switch (name) {
            case "sticky_affinity" -> {
                out.add(metric("活跃亲和", stickyAffinity.activeCount()));
                out.add(metric("粘性命中", stickyAffinity.stickyHits()));
                out.add(metric("Key 切换", stickyAffinity.keySwitches()));
            }
            case "chat_pii_redaction" -> out.addAll(moduleMetrics.metricsOf(name));
            case "important_notification", "server_chan_push", "bark_push" -> {
                NotificationService svc = notificationProvider.getIfAvailable();
                if (svc != null) {
                    out.add(metric("已发送", svc.sentCount()));
                    out.add(metric("发送失败", svc.failedCount()));
                }
            }
            case "config_backup" -> {
                ConfigBackupJob job = backupProvider.getIfAvailable();
                if (job != null) {
                    out.add(metric("现存备份", job.backupCount()));
                }
            }
            case "disk_cache" -> out.addAll(moduleMetrics.metricsOf(name));
            default -> {
            }
        }
        return out;
    }

    private static Map<String, Object> metric(String label, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        return m;
    }

    /** 模块状态全量（按默认顺序）。 */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModuleDef def : catalog.defs()) {
            boolean available = catalog.available(def);
            boolean enabled = toggles.enabled(def.name(), def.defaultEnabled());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", def.name());
            m.put("displayName", def.displayName());
            m.put("description", def.description());
            m.put("category", def.category());
            m.put("available", available);
            m.put("enabled", enabled);
            m.put("active", available && enabled);
            m.put("configValidated", available);
            m.put("configError", available ? null : catalog.unavailableHint(def));
            m.put("adminRoute", def.adminRoute());
            m.put("order", def.order());
            out.add(m);
        }
        return out;
    }

    public void setEnabled(String name, boolean enabled) {
        if (!catalog.known(name)) {
            throw new IllegalArgumentException("未知模块: " + name);
        }
        systemConfig.put("module." + name + ".enabled", enabled, "模块开关（模块管理页）");
        toggles.invalidate(name);
    }

    public List<String> getOrder() {
        Object raw = systemConfig.getParsed(ORDER_KEY);
        if (raw == null) {
            return List.of();
        }
        try {
            return JsonUtils.parse(JsonUtils.toJson(raw), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("模块卡片顺序解析失败（回落默认顺序）: {}", e.toString());
            return List.of();
        }
    }

    public void setOrder(List<String> order) {
        if (order == null || order.isEmpty()) {
            systemConfig.delete(ORDER_KEY);
        } else {
            systemConfig.put(ORDER_KEY, order, "模块卡片自定义顺序");
        }
    }
}
