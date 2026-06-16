package com.nexusai.control.domain.project.accesskey.dto;

import java.math.BigDecimal;

/**
 * 独立 Key 元数据更新请求。禁止修改 {@code keyHash}，轮转走独立接口。
 *
 * @author nexusai
 */
public record AccessKeyUpdateRequest(
        String name,
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
        Long expiredTime,
        Integer autoDeleteOnExpiry,
        String featureSettings,
        Integer status) {
}
