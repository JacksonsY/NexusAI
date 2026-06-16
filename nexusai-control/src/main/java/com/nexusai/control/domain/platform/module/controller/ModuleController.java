package com.nexusai.control.domain.platform.module.controller;

import com.nexusai.common.model.R;
import com.nexusai.control.domain.platform.module.vo.TransformerDescriptorVo;
import com.nexusai.provider.spi.ProviderFactory;
import com.nexusai.task.spi.TaskAdapterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块管理（/admin/modules·只读）：代码式适配的 <b>SPI 注册表</b>（Provider / ChatTransformer / TaskAdapter）
 * + <b>特性开关态</b>（guardrail / response-cache / log-sharding / usage-batch）。
 * 注：多实例熔断态共享已升为<b>永久架构属性</b>（始终 RTopic 广播·非开关），故不在特性开关里。
 * <p>「协议家族=代码」的可视化：加新上游协议 = 新增一个 @Bean Provider/Transformer，本页即时反映。
 * 开关为编译期 @Value 只读快照（改需改配置重启·非热切）。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final ProviderFactory providerFactory;
    private final TaskAdapterRegistry taskAdapterRegistry;

    @Value("${nexusai.guardrail.enabled:false}")
    private boolean guardrailEnabled;
    @Value("${nexusai.cache.response.enabled:false}")
    private boolean responseCacheEnabled;
    @Value("${nexusai.log.sharding.enable:false}")
    private boolean logShardingEnabled;
    @Value("${nexusai.usage.batch.enabled:false}")
    private boolean usageBatchEnabled;

    @GetMapping
    public Mono<R<Map<String, Object>>> modules() {
        Map<String, Object> data = new LinkedHashMap<>();
        // SPI 注册表（代码式适配·只读）
        data.put("providers", providerFactory.descriptors());
        data.put("transformers", List.of(
                new TransformerDescriptorVo("OpenAiChatTransformer", "OPENAI"),
                new TransformerDescriptorVo("AnthropicChatTransformer", "ANTHROPIC"),
                new TransformerDescriptorVo("GeminiChatTransformer", "GEMINI")));
        data.put("taskAdapters", taskAdapterRegistry.descriptors());
        // 特性开关态（编译期 @Value 只读快照）
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("guardrail", guardrailEnabled);
        features.put("responseCache", responseCacheEnabled);
        features.put("logSharding", logShardingEnabled);
        features.put("usageBatch", usageBatchEnabled);
        data.put("features", features);
        return Mono.just(R.ok(data));
    }
}
