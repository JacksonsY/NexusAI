package com.nexusai.protocol.transform;

import com.nexusai.protocol.canonical.CanonicalImage;

/**
 * 图片归一辅助：OpenAI/Gemini 的 base64 dataURI（{@code data:<mediaType>;base64,<data>}）与
 * {@link CanonicalImage} 三元组互拆/互拼。Anthropic 的 {@code source{media_type,data}} 已是分离三元组，直接构造即可。
 *
 * @author nexusai
 */
final class CanonicalImages {

    private static final String DATA_PREFIX = "data:";
    private static final String BASE64_MARK = ";base64,";
    private static final String DEFAULT_MEDIA_TYPE = "image/png";

    private CanonicalImages() {
    }

    /** 拆 dataURI → CanonicalImage；非 base64 dataURI（如远程 URL）返回 null（无法归一为 base64 块）。 */
    static CanonicalImage fromDataUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith(DATA_PREFIX)) {
            return null;
        }
        int mark = dataUri.indexOf(BASE64_MARK);
        if (mark < 0) {
            return null;
        }
        String mediaType = dataUri.substring(DATA_PREFIX.length(), mark);
        String data = dataUri.substring(mark + BASE64_MARK.length());
        if (mediaType.isEmpty()) {
            mediaType = DEFAULT_MEDIA_TYPE;
        }
        return CanonicalImage.base64(mediaType, data);
    }

    /** 拼 CanonicalImage → dataURI（OpenAI image_url.url / Gemini 无需·用 inlineData）。 */
    static String toDataUri(CanonicalImage image) {
        String mediaType = image.mediaType() == null || image.mediaType().isEmpty() ? DEFAULT_MEDIA_TYPE : image.mediaType();
        return DATA_PREFIX + mediaType + BASE64_MARK + (image.base64Data() == null ? "" : image.base64Data());
    }
}
