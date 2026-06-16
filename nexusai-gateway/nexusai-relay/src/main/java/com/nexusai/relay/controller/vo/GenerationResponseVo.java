package com.nexusai.relay.controller.vo;

/**
 * {@code GET /v1/generation/{requestId}} 响应外壳 {@code {data:{...}}}。
 *
 * @author nexusai
 */
public record GenerationResponseVo(GenerationDataVo data) {
}
