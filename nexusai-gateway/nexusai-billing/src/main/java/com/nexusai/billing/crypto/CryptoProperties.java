package com.nexusai.billing.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上游凭证 AES-256-GCM 加密配置（版本化 keyId 轮转）。
 * <ul>
 *   <li>{@code nexusai.crypto.keys.<keyId>}：轮转密钥表（keyId → Base64 密钥·至少配一个如 {@code keys.v1}）</li>
 *   <li>{@code nexusai.crypto.active-key-id}：当前加密使用的 keyId（须在 keys 中存在）</li>
 * </ul>
 * 由 {@link CredentialCipher} 注入；密钥经环境变量注入，绝不硬编码。
 * <p><b>放于 nexusai-billing（共享 Spring 模块）</b>：relay（解密调上游）与 control-domain（admin 加密落库）都依赖 billing，
 * 凭此让 admin 写库与 relay 读库共用同一版本化轮转密钥逻辑。
 *
 * @author nexusai
 */
@Data
@Component
@ConfigurationProperties(prefix = "nexusai.crypto")
public class CryptoProperties {

    /** 轮转密钥表：keyId → Base64 密钥（至少配一个）。 */
    private Map<String, String> keys = new LinkedHashMap<>();

    /** 当前加密使用的 keyId（须在 keys 中存在）。 */
    private String activeKeyId;
}
