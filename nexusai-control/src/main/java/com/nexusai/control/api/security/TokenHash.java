package com.nexusai.control.api.security;

import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 控制面令牌单向哈希（SHA-256·小写 hex·无盐确定性）：M2M 项目令牌（{@code nai_project.token}）与独立 Access Key
 * （{@code nai_token.key_hash}）沿用业务面 sk- 同一存储范式——明文永不落库，DB 仅存 hash，鉴权按 {@code sha256(入参)}
 * 查表命中。哈希查表本身抗计时（攻击者需对 SHA-256 求原像才能命中，比对的是 hash 而非秘密），故无需额外恒时比较。
 *
 * @author nexusai
 */
public final class TokenHash {

    private TokenHash() {
    }

    /** 明文 → SHA-256 小写 hex（与业务面 sk- keyHash 同算法·可跨面互查）。 */
    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw GatewayException.of(GatewayErrorCode.INTERNAL_ERROR, "SHA-256 计算失败");
        }
    }
}
