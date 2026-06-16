package com.nexusai.protocol.canonical;

/**
 * Canonical 图片块（中枢中立）：作为各家图片表示互转的归一中枢——
 * OpenAI {@code image_url}（dataURI 或<b>远程 URL</b>）、Anthropic {@code source{base64|url}}、
 * Gemini {@code inlineData(base64) | fileData(URL)} 三者互转都先落此结构。
 * <p>两种形态二选一：<b>base64 内联</b>（mediaType+base64Data）或 <b>远程 URL</b>（url）。
 * 远程 URL 形态保证 OpenAI image_url 远程链接不被静默丢弃（{@link #isUrl()}）。
 *
 * @param mediaType  媒体类型（如 {@code image/png}）·base64 形态用
 * @param base64Data base64 编码的原始图片数据（不含 dataURI 前缀）·base64 形态用
 * @param url        远程图片 URL（http/https）·URL 形态用·与 base64Data 互斥
 * @author nexusai
 */
public record CanonicalImage(String mediaType, String base64Data, String url) {

    /** base64 内联图片（OpenAI dataURI / Anthropic base64 source / Gemini inlineData 互转中枢）。 */
    public static CanonicalImage base64(String mediaType, String base64Data) {
        return new CanonicalImage(mediaType, base64Data, null);
    }

    /** 远程 URL 图片（OpenAI 远程 image_url / Anthropic url source / Gemini fileData·不内联 base64）。 */
    public static CanonicalImage url(String url) {
        return new CanonicalImage(null, null, url);
    }

    /** 是否远程 URL 形态（无 base64·按各家 URL 表示透传·不可当 base64 用）。 */
    public boolean isUrl() {
        return url != null && !url.isBlank();
    }
}
