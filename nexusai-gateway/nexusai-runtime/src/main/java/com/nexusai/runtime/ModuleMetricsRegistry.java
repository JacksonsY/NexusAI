package com.nexusai.runtime;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 功能模块运行指标注册表（{@code CacheRegistry} 同款自注册范式）：数据面组件（relay/router 等）启动时
 * 注册本模块的指标快照供给器，控制面模块管理页（control-domain ModuleService）只读本注册表——
 * 解除控制面对数据面具体类型的依赖（control 不依赖 gateway 的铁律）。
 * <p>供给器异常吞掉返回空列表（指标是尽力而为，绝不影响管理页主链）。
 *
 * @author nexusai
 */
@Component
public class ModuleMetricsRegistry {

    private final Map<String, Supplier<List<Map<String, Object>>>> suppliers = new ConcurrentHashMap<>();

    /** 注册模块指标供给器（module = 模块名，如 chat_pii_redaction / disk_cache）。 */
    public void register(String module, Supplier<List<Map<String, Object>>> supplier) {
        suppliers.put(module, supplier);
    }

    /** 模块当前指标快照（未注册/供给异常 → 空列表）。 */
    public List<Map<String, Object>> metricsOf(String module) {
        Supplier<List<Map<String, Object>>> s = suppliers.get(module);
        if (s == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> out = s.get();
            return out == null ? List.of() : out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 指标条目（label/value 有序，与模块抽屉渲染约定一致）。 */
    public static Map<String, Object> metric(String label, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        return m;
    }
}
