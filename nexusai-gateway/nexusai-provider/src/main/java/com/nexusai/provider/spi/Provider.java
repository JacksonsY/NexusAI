package com.nexusai.provider.spi;

import com.nexusai.common.enums.ApiFormat;

/**
 * Provider SPI（协议家族适配）。
 * <p>透传直采下职责极薄：把"选号后的渠道+Key+模型+原体"适配成上游请求描述符（换鉴权头/模型名/端点）。
 * per-provider 实现由 Spring List 注入自动收集，{@link ProviderFactory} 按 {@link #supports} 精确匹配。
 *
 * @author nexusai
 */
public interface Provider {

    /** 本 Provider 处理的协议家族 */
    ApiFormat apiFormat();

    /**
     * 精确匹配键（精确匹配 + openai 兼容回退，禁裸 findFirst）。
     *
     * @param providerKey 渠道声明的协议家族键（如 "openai" / "openai-compatible" / "claude"）
     */
    boolean supports(String providerKey);

    /** 适配为上游请求描述符 */
    UpstreamRequest adapt(UpstreamContext ctx);
}
