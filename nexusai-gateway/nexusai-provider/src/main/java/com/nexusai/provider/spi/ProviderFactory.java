package com.nexusai.provider.spi;

import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provider 选择工厂：
 * <b>精确匹配 providerKey；未命中回退 openai-compatible；仍无则显式拒绝（禁裸 findFirst 静默兜底）。</b>
 * Provider 列表由 Spring 构造注入自动收集（SPI）。
 *
 * @author nexusai
 */
public class ProviderFactory {

    private final List<Provider> providers;
    private final Provider openAiCompatible;

    public ProviderFactory(List<Provider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.openAiCompatible = providers.stream()
                .filter(p -> p.supports("openai-compatible"))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按渠道协议家族键解析 Provider。
     *
     * @param providerKey 渠道声明的协议家族（如 "openai"/"claude"/"gemini"）
     */
    public Provider resolve(String providerKey) {
        Provider hit = providers.stream()
                .filter(p -> p.supports(providerKey))
                .findFirst()
                .orElse(openAiCompatible);   // 回退 openai 兼容（档①绝大多数上游）
        if (hit == null) {
            throw GatewayException.of(GatewayErrorCode.NO_CHANNEL_AVAILABLE,
                    "无匹配 Provider 且无 openai-compatible 兜底: " + providerKey);
        }
        return hit;
    }

    /**
     * SPI 注册表描述（模块管理只读视图）：每个 Provider 的实现类名 + 适配的协议家族。
     * <p>返回 {@code List<Map>} 而非 VO：消费方是 nexusai-control 的 ModuleService/ModuleController（跨分区），
     * 为不在数据面与控制面间硬绑类型而保留松散 Map 契约（key={@code provider}/{@code apiFormat}）。
     */
    public List<Map<String, String>> descriptors() {
        return providers.stream()
                .map(p -> Map.of(
                        "provider", p.getClass().getSimpleName(),
                        "apiFormat", String.valueOf(p.apiFormat())))
                .toList();
    }
}
