package com.nexusai.task.spi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 任务适配器注册表：收集所有 {@link TaskAdapter}（可为空·MVP 无具体 adapter 时退化为不处理），按 modality 路由。
 * <p>把 {@code TaskPoller} 的「上游 fetch 集成点」从死注释变成<b>真可插拔扩展点</b>：新增上游协议只需加一个
 * {@code @Component implements TaskAdapter}，无需改轮询器。
 *
 * @author nexusai
 */
@Component
public class TaskAdapterRegistry {

    private final List<TaskAdapter> adapters;

    public TaskAdapterRegistry(ObjectProvider<TaskAdapter> provider) {
        this.adapters = provider.orderedStream().toList();
    }

    /** 按模态解析首个支持的适配器（无则 empty → 轮询器不处理该任务，保留进行中待 adapter 上线）。 */
    public Optional<TaskAdapter> resolve(String modality) {
        return adapters.stream().filter(a -> a.supports(modality)).findFirst();
    }

    public boolean isEmpty() {
        return adapters.isEmpty();
    }

    public int size() {
        return adapters.size();
    }

    /** SPI 注册表描述（模块管理只读视图）：每个 TaskAdapter 实现类名。 */
    public List<String> descriptors() {
        return adapters.stream().map(a -> a.getClass().getSimpleName()).toList();
    }
}
