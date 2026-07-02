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

    public ProviderFactory(List<Provider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    /**
     * 按渠道协议家族键（{@code nai_channel.protocol}）<b>精确</b>解析 Provider。
     * <p><b>禁静默兜底</b>：openai 兼容变体（openai/openai-compatible/oneapi/newapi 等）由
     * {@code OpenAiCompatibleProvider.supports()} 白名单精确命中而解析到 openai-compatible；其余未知/拼错的协议
     * 直接抛配置错误——不再盲目回退 openai-compatible（否则错配鉴权头 → 连环 401 → 健康 Key 被误封）。
     *
     * @param providerKey 渠道声明的协议家族（如 "openai"/"claude"/"gemini"）
     */
    public Provider resolve(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            throw GatewayException.of(GatewayErrorCode.NO_CHANNEL_AVAILABLE, "渠道未声明协议家族(protocol)");
        }
        return providers.stream()
                .filter(p -> p.supports(providerKey))
                .findFirst()
                .orElseThrow(() -> GatewayException.of(GatewayErrorCode.NO_CHANNEL_AVAILABLE,
                        "未知渠道协议家族(protocol)=" + providerKey + "：无匹配 Provider（疑似协议配置错误·禁静默兜底 openai-compatible）"));
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
