package com.nexusai.common.enums;

/**
 * 协议家族（出/入站格式）。MVP 仅 OPENAI 透传直采；其余 P2~P4 原生适配。
 *
 * @author nexusai
 */
public enum ApiFormat {
    /** OpenAI 兼容（MVP 主路） */
    OPENAI,
    /** Anthropic Claude（/v1/messages 信封） */
    ANTHROPIC,
    /** Google Gemini（/v1beta/models 协议） */
    GEMINI
}
