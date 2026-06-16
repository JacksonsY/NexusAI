package com.nexusai.control.domain.routing.credential.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexusai.billing.crypto.CredentialCipher;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.persistence.mapper.NaiChannelKeyMapper;
import com.nexusai.persistence.po.NaiChannelKey;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 渠道密钥 Key（上游凭证）域服务：凭证 AES-256-GCM 加密落库、列表脱敏（仅回指纹·不回明文/密文）、
 * 更新时仅当传入新明文才重加密。<b>安全敏感逻辑从控制器下沉集中一处</b>（密钥使用/脱敏/重加密判定）。
 * <p><b>P7 修复</b>：加密走共享 {@link CredentialCipher}（版本化轮转·active-key-id 加 keyId 前缀），
 * 不再旁路 legacy 单密钥，使 AES 主密钥轮转对 admin 写库真实生效（与 relay 解密同源）。
 *
 * @author nexusai
 */
@Service
public class CredentialAdminService {

    /** 凭证指纹：短于此长度直接全掩码 */
    private static final int MASK_MIN_LENGTH = 8;
    /** 凭证指纹：保留尾部位数 */
    private static final int MASK_SUFFIX_LENGTH = 4;
    /** 渠道密钥默认权重/优先级（「URL + Key 一体填写」首条凭证） */
    private static final int DEFAULT_KEY_WEIGHT = 10;
    private static final int DEFAULT_KEY_PRIORITY = 1;
    /** 启用状态值 */
    private static final int STATUS_ENABLED = 1;
    private static final String MASK_PREFIX = "***";
    /** 加密自检探针明文（保存渠道前确认本节点可加密） */
    private static final String CRYPTO_PROBE = "__nexusai_crypto_probe__";

    private final NaiChannelKeyMapper keyMapper;
    private final CredentialCipher cipher;

    public CredentialAdminService(NaiChannelKeyMapper keyMapper, CredentialCipher cipher) {
        this.keyMapper = keyMapper;
        this.cipher = cipher;
    }

    /** 列表（凭证脱敏·仅回指纹）。 */
    public List<NaiChannelKey> list(Long channelId) {
        List<NaiChannelKey> keys = keyMapper.selectList(new LambdaQueryWrapper<NaiChannelKey>()
                .eq(channelId != null, NaiChannelKey::getChannelId, channelId));
        keys.forEach(k -> k.setCredential(mask(k.getCredential())));
        return keys;
    }

    /** 新增：明文凭证 AES-GCM 加密后落库。 */
    public Long create(NaiChannelKey key) {
        key.setId(null);
        key.setCredential(encrypt(requirePlain(key.getCredential())));
        keyMapper.insert(key);
        return key.getId();
    }

    /** 更新：仅当传入新明文（非 *** 脱敏占位）才重加密，否则不覆盖凭证。 */
    public void update(Long id, NaiChannelKey key) {
        key.setId(id);
        if (StringUtils.hasText(key.getCredential()) && !key.getCredential().startsWith(MASK_PREFIX)) {
            key.setCredential(encrypt(key.getCredential()));
        } else {
            key.setCredential(null);
        }
        keyMapper.updateById(key);
    }

    public void delete(Long id) {
        keyMapper.deleteById(id);
    }

    /**
     * 渠道表单「URL + Key 一体填写」落点（用户拍板：本项目无渠道密钥概念）：
     * 该渠道已有凭证则重加密更新首条，否则创建一条默认凭证。明文为空/脱敏占位则不动。
     */
    public void upsertForChannel(Long channelId, String plainCredential) {
        if (!StringUtils.hasText(plainCredential) || plainCredential.startsWith(MASK_PREFIX)) {
            return;
        }
        String encrypted = encrypt(plainCredential);
        NaiChannelKey existing = keyMapper.selectList(new LambdaQueryWrapper<NaiChannelKey>()
                        .eq(NaiChannelKey::getChannelId, channelId)
                        .orderByAsc(NaiChannelKey::getId))
                .stream().findFirst().orElse(null);
        if (existing != null) {
            NaiChannelKey patch = new NaiChannelKey();
            patch.setId(existing.getId());
            patch.setCredential(encrypted);
            patch.setStatus(STATUS_ENABLED);
            keyMapper.updateById(patch);
            return;
        }
        NaiChannelKey key = new NaiChannelKey();
        key.setChannelId(channelId);
        key.setCredential(encrypted);
        key.setWeight(DEFAULT_KEY_WEIGHT);
        key.setPriority(DEFAULT_KEY_PRIORITY);
        key.setStatus(STATUS_ENABLED);
        keyMapper.insert(key);
    }

    /** 保存渠道前置校验：需要写入新凭证时，先确认本节点可加密，避免渠道配置已写入但凭证保存失败。 */
    public void ensureEncryptionReadyIfChanging(String plainCredential) {
        if (!StringUtils.hasText(plainCredential) || plainCredential.startsWith(MASK_PREFIX)) {
            return;
        }
        encrypt(CRYPTO_PROBE);
    }

    /** 删除渠道下全部凭证（渠道批量删除连带清理）。 */
    public void deleteByChannel(Long channelId) {
        keyMapper.delete(new LambdaQueryWrapper<NaiChannelKey>()
                .eq(NaiChannelKey::getChannelId, channelId));
    }

    /** 渠道复制：把源渠道渠道密钥整体拷到新渠道（密文直接复制·同一主密钥可解，无需解/重加密）。 */
    public void copyChannelKeys(Long srcChannelId, Long dstChannelId) {
        for (NaiChannelKey k : keyMapper.selectList(new LambdaQueryWrapper<NaiChannelKey>()
                .eq(NaiChannelKey::getChannelId, srcChannelId))) {
            k.setId(null);
            k.setChannelId(dstChannelId);
            k.setSuccessCount(0L);
            k.setErrorCount(0L);
            k.setHealthScore(100);
            k.setRateLimitResetAt(0L);
            k.setOverloadUntil(0L);
            k.setTempUnschedulableUntil(0L);
            keyMapper.insert(k);
        }
    }

    /** 渠道首条凭证指纹（详情展示·无则 null）。 */
    public String maskedForChannel(Long channelId) {
        return keyMapper.selectList(new LambdaQueryWrapper<NaiChannelKey>()
                        .eq(NaiChannelKey::getChannelId, channelId)
                        .orderByAsc(NaiChannelKey::getId))
                .stream().findFirst().map(k -> mask(k.getCredential())).orElse(null);
    }

    private String encrypt(String plain) {
        try {
            return cipher.encrypt(plain);   // 版本化轮转加密（active-key-id 前缀）·与 relay 解密同源
        } catch (IllegalStateException e) {
            throw new IllegalStateException("凭证密钥未配置 nexusai.crypto.active-key-id + keys.*", e);
        }
    }

    private static String requirePlain(String credential) {
        if (!StringUtils.hasText(credential)) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "凭证 credential 不能为空");
        }
        return credential;
    }

    private static String mask(String cipher) {
        if (cipher == null || cipher.length() < MASK_MIN_LENGTH) {
            return MASK_PREFIX;
        }
        return MASK_PREFIX + cipher.substring(cipher.length() - MASK_SUFFIX_LENGTH);
    }
}
