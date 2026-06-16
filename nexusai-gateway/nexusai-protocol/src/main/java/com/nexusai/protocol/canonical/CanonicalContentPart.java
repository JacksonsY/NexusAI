package com.nexusai.protocol.canonical;

/**
 * Canonical 内容块（中枢中立）：一条消息的内容由若干块组成，{@code type} 区分文本/图片，
 * 取代旧的纯文本 content 字符串，使多模态请求跨协议互转（OpenAI↔Anthropic↔Gemini）时图片块不被拍平丢弃。
 *
 * @param type  块类型：{@code text} | {@code image}
 * @param text  文本内容（{@code type=text} 时有效）
 * @param image 图片内容（{@code type=image} 时有效）
 * @author nexusai
 */
public record CanonicalContentPart(String type, String text, CanonicalImage image) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";

    public static CanonicalContentPart text(String text) {
        return new CanonicalContentPart(TYPE_TEXT, text, null);
    }

    public static CanonicalContentPart image(CanonicalImage image) {
        return new CanonicalContentPart(TYPE_IMAGE, null, image);
    }

    public boolean isText() {
        return TYPE_TEXT.equals(type);
    }

    public boolean isImage() {
        return TYPE_IMAGE.equals(type) && image != null;
    }
}
