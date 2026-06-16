package com.nexusai.relay.cache;

/**
 * LLM 响应缓存 SPI：命中即短路返回缓存响应，省一次上游调用 + USD 额度。
 * <p>默认实现 {@link ExactResponseCache} 为<b>精确匹配</b>（key=sha256(scope+model+body)·非流式）；
 * <b>语义匹配</b>（embedding 向量相似召回）需接入 embedding provider + 向量库，作为同 SPI 的扩展实现（产品决策后）。
 * 仅 {@code nexusai.cache.response.enabled=true} 装配。
 * <p>🔴 {@code scope}（租户边界·通常项目码）必须进 key：缺它则不同租户相同 model+body 会命中彼此缓存——
 * 既泄露他租户响应、又让命中方免结算白嫖（命中退冻结却不计费）。
 *
 * @author nexusai
 */
public interface ResponseCache {

    /** 取缓存响应体（命中=原样 JSON 字节·未命中/未启用=null）。scope=租户边界（项目码）。 */
    byte[] get(String scope, String model, byte[] requestBody);

    /** 存响应体（成功非流式响应后调用）。scope=租户边界（项目码）。 */
    void put(String scope, String model, byte[] requestBody, byte[] responseBody);

    /** 是否启用（调用方据此跳过查/存）。 */
    boolean enabled();
}
