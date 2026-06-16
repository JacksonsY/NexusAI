package com.nexusai.relay;

import com.nexusai.common.enums.ApiFormat;

/**
 * 入站路径 → 模态 / API 格式语义的纯静态映射（「协议族:端点语义」形态）。
 * 编排器、计量上报、上游交换多个协作类共用，无状态。
 *
 * @author nexusai
 */
public final class RelayFormats {

    private RelayFormats() {
    }

    /** 入站路径 → 模态（chat/embedding/image/audio/rerank）。completions/moderations/responses/edits 归 chat（文本·token 计费）。 */
    public static String modalityOf(String path) {
        if (path == null) {
            return "chat";
        }
        if (path.contains("/audio/")) {
            return "audio";
        }
        if (path.contains("/embeddings") || path.contains("embedContent")) {   // OpenAI /embeddings + Gemini :embedContent/:batchEmbedContents
            return "embedding";
        }
        if (path.contains("/images")) {
            return "image";
        }
        if (path.contains("/rerank")) {
            return "rerank";
        }
        return "chat";
    }

    /** 入站协议家族（/v1/messages=Anthropic·/v1beta/models=Gemini·其余 OpenAI 兼容）。 */
    public static ApiFormat inboundFormat(String path) {
        if (path == null) {
            return ApiFormat.OPENAI;
        }
        if (path.contains("/messages")) {
            return ApiFormat.ANTHROPIC;
        }
        if (path.contains("/v1beta/models/")) {
            return ApiFormat.GEMINI;
        }
        return ApiFormat.OPENAI;
    }

    /** 入站 API 格式标识（协议族:端点语义）。 */
    public static String apiFormatOf(String path, String modality) {
        if (path != null) {
            if (path.contains("/messages")) {
                return "claude:messages";
            }
            if (path.startsWith("/v1beta")) {
                return "gemini:generateContent";
            }
            if (path.contains("/audio/")) {
                return "openai:audio";
            }
            if (path.contains("/responses")) {
                return "openai:responses";
            }
            if (path.contains("/moderations")) {
                return "openai:moderations";
            }
        }
        return switch (modality) {
            case "embedding" -> "openai:embeddings";
            case "image" -> "openai:images";
            case "rerank" -> "openai:rerank";
            case "audio" -> "openai:audio";
            default -> "openai:chat";
        };
    }

    /** 上游端点 API 格式（渠道协议族推导·与入站不同即发生格式转换）。 */
    public static String endpointApiFormatOf(String protocol, String modality) {
        String p = protocol == null ? "" : protocol.toUpperCase();
        if (p.contains("ANTHROPIC")) {
            return "claude:messages";
        }
        if (p.contains("GEMINI")) {
            return "gemini:generateContent";
        }
        // OpenAI 兼容族（含 DeepSeek/通义千问等）：随模态定端点语义
        return switch (modality) {
            case "embedding" -> "openai:embeddings";
            case "image" -> "openai:images";
            case "rerank" -> "openai:rerank";
            case "audio" -> "openai:audio";
            default -> "openai:chat";
        };
    }
}
