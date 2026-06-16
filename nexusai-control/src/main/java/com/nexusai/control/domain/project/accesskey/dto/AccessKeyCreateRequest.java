package com.nexusai.control.domain.project.accesskey.dto;

import java.math.BigDecimal;

/**
 * 网关控制台独立签发下游 Access Key 请求。
 *
 * @author nexusai
 */
public record AccessKeyCreateRequest(
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
        String featureSettings) {
}
