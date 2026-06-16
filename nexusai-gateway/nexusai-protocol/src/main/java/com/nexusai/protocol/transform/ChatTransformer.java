package com.nexusai.protocol.transform;

import com.nexusai.common.enums.ApiFormat;
import com.nexusai.protocol.canonical.CanonicalChatRequest;
import com.nexusai.protocol.canonical.CanonicalChatResponse;

/**
 * 聊天 Inbound/Outbound 互转器（入站/出站 ↔ Canonical 中枢中立表示）。
 * 每协议家族一实现：入站(请求/响应) ↔ Canonical ↔ 出站(请求/响应)。MVP 非流式；流式 SSE 转码留后续。
 *
 * @author nexusai
 */
public interface ChatTransformer {

    ApiFormat format();

    /** 入站请求体 → Canonical */
    CanonicalChatRequest toCanonicalRequest(byte[] body);

    /** Canonical → 本格式出站请求体 */
    byte[] fromCanonicalRequest(CanonicalChatRequest req);

    /** 本格式响应体 → Canonical */
    CanonicalChatResponse toCanonicalResponse(byte[] body);

    /** Canonical → 本格式响应体 */
    byte[] fromCanonicalResponse(CanonicalChatResponse resp);
}
