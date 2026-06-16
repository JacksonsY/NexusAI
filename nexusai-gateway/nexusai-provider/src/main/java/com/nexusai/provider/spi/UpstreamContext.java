package com.nexusai.provider.spi;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Provider 适配输入：选号后的渠道+Key+模型+原始请求，供 Provider 产出上游请求描述符。
 * <p>用 {@link Getter}/{@link Setter} 而非 {@code @Data}：含 {@code byte[] rawBody}（数组）+ 明文凭证，
 * 不生成 equals/hashCode/toString——避免对请求体逐字节比较、以及凭证经 toString 泄入日志/异常栈。
 *
 * @author nexusai
 */
@Getter
@Setter
@Builder
public class UpstreamContext {

    /** 渠道 base_url（如 https://api.openai.com） */
    private String baseUrl;

    /** 上游凭证（已 AES-GCM 解密的明文 Key）·无 toString 生成故不泄入日志/异常栈 */
    private String upstreamApiKey;

    /** 目标模型（已过 model_mapping 重映射后的上游真实模型名） */
    private String targetModel;

    /** 入站路径（如 /v1/chat/completions），透传直采下原样转发 */
    private String path;

    /** 原始请求体（透传；流式已强制 include_usage） */
    private byte[] rawBody;

    /** 渠道级 header 覆盖（header_override） */
    private Map<String, String> headerOverride;

    /** 是否流式 */
    private boolean stream;
}
