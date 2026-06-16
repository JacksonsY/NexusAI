package com.nexusai.persistence.log.body;

import com.nexusai.persistence.log.RequestBodyBlob;
import com.nexusai.persistence.log.RequestBodyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认正文存储档（{@code nexusai.log.body.store=hash} 或不配）：<b>不存正文</b>，日志主表仅保留 sha256(prompt_hash)。
 * 默认行为（最省·隐私/合规友好）。{@link #isCollecting()} 恒 false → 调用方跳过 body 缓冲。
 *
 * @author nexusai
 */
@Component
@ConditionalOnProperty(prefix = "nexusai.log.body", name = "store", havingValue = "hash", matchIfMissing = true)
public class NoopBodyStore implements RequestBodyStore {

    @Override
    public String save(String requestId, byte[] requestBody, byte[] responseBody) {
        return null;
    }

    @Override
    public RequestBodyBlob load(String requestId) {
        return null;
    }

    @Override
    public boolean isCollecting() {
        return false;
    }
}
