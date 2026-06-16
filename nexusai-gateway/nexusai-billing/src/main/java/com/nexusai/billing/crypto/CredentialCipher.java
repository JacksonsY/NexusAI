package com.nexusai.billing.crypto;

import com.nexusai.common.crypto.AesGcmHelper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 上游凭证加解密服务（AES-256-GCM·版本化密钥轮转·无向后兼容旧单密钥）：
 * <ul>
 *   <li>encrypt：用 active-key-id 对应密钥加密并加 {@code keyId:} 前缀；未配置 active-key-id 则报错（强制版本化）；</li>
 *   <li>decrypt：按密文 {@code keyId:} 前缀选对应轮转密钥；前缀缺失/未知 → 报错（不静默猜测）。</li>
 * </ul>
 * AES-GCM 密文为标准 Base64（不含 ':'），故以首个 ':' 安全切分 keyId（配置 {@link CryptoProperties}）。
 * <p><b>唯一加解密入口</b>：relay 读库解密、control-domain admin 写库加密都注入此服务，保证轮转对落库真实生效。
 *
 * @author nexusai
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialCipher {

    private final CryptoProperties props;

    private final Map<String, AesGcmHelper> helpers = new HashMap<>();
    private String activeKeyId;

    @PostConstruct
    void init() {
        props.getKeys().forEach((id, b64) -> {
            if (StringUtils.hasText(id) && StringUtils.hasText(b64)) {
                helpers.put(id, AesGcmHelper.fromBase64Key(b64));
            }
        });
        activeKeyId = props.getActiveKeyId();
        if (helpers.isEmpty()) {
            // BYOK 凭证加密为可选能力：完全未配密钥时仅告警（不阻断不使用 BYOK 的部署）
            log.warn("nexusai.crypto.keys 未配置任何密钥：上游凭证将无法加解密（请注入 keys.<id> + active-key-id）");
        } else if (!StringUtils.hasText(activeKeyId)) {
            // 已配密钥却缺 active-key-id：确定性的致命误配，fail-fast 阻止启动而非运行时才暴露
            throw new IllegalStateException("nexusai.crypto.active-key-id 未配置：已配置 keys 但无生效密钥，加密无法工作");
        } else if (!helpers.containsKey(activeKeyId)) {
            throw new IllegalStateException("nexusai.crypto.active-key-id=" + activeKeyId + " 在 keys 中不存在：配置错误");
        }
    }

    public String encrypt(String plain) {
        if (StringUtils.hasText(activeKeyId) && helpers.containsKey(activeKeyId)) {
            return activeKeyId + ":" + helpers.get(activeKeyId).encrypt(plain);
        }
        throw new IllegalStateException("凭证加密失败：nexusai.crypto.active-key-id 未配置或不在 keys 中");
    }

    public String decrypt(String cipherText) {
        int i = cipherText == null ? -1 : cipherText.indexOf(':');
        if (i > 0) {
            AesGcmHelper h = helpers.get(cipherText.substring(0, i));
            if (h != null) {
                return h.decrypt(cipherText.substring(i + 1));
            }
        }
        throw new IllegalStateException("无法解密凭证：密文缺少 keyId 前缀或对应密钥未配置（nexusai.crypto.keys）");
    }
}
