package com.nexusai.control.domain.project.accesskey.dto;

import com.nexusai.persistence.po.NaiToken;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 独立密钥列表展示 VO：仅暴露控制台需要的安全字段。
 * <p><b>安全</b>：绝不外泄 {@code keyHash}（鉴权真值）与 {@code byokCredential}（AES-GCM 密文）——
 * 前者仅以末 4 位指纹展示，后者仅以布尔标记是否配置 BYOK。
 *
 * @author nexusai
 */
public record AccessKeyListItem(
        Long id,
        String name,
        Integer standalone,
        String projectCode,
        Long workspaceId,
        String groupCode,
        String modelLimits,
        String allowIps,
        String allowProviders,
        String allowApiFormats,
        BigDecimal quotaUsd,
        Integer rpmLimit,
        Integer tpmLimit,
        Integer concurrentLimit,
        Boolean byok,
        String keyFingerprint,
        Long expiredTime,
        Integer autoDeleteOnExpiry,
        String featureSettings,
        Integer status,
        LocalDateTime createDt,
        LocalDateTime updateDt) {

    /** 由 PO 投影为安全 VO（脱敏 keyHash·屏蔽 byokCredential 密文）。 */
    public static AccessKeyListItem from(NaiToken t) {
        return new AccessKeyListItem(
                t.getId(), t.getName(), t.getStandalone(), t.getProjectCode(), t.getWorkspaceId(),
                t.getGroupCode(), t.getModelLimits(), t.getAllowIps(), t.getAllowProviders(), t.getAllowApiFormats(),
                t.getQuotaUsd(), t.getRpmLimit(), t.getTpmLimit(), t.getConcurrentLimit(),
                t.getByokCredential() != null && !t.getByokCredential().isBlank(),
                fingerprint(t.getKeyHash()),
                t.getExpiredTime(), t.getAutoDeleteOnExpiry(), t.getFeatureSettings(), t.getStatus(),
                t.getCreateDt(), t.getUpdateDt());
    }

    private static String fingerprint(String keyHash) {
        if (keyHash == null || keyHash.length() < 8) {
            return null;
        }
        return "***" + keyHash.substring(keyHash.length() - 8);
    }
}
