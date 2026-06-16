package com.nexusai.control.domain.project.accesskey.dto;

import java.math.BigDecimal;

/**
 * 独立 Key 额度调整请求体（{@code {quotaUsd}}·USD·null/≤0=不设上限·仅受项目池约束）。
 *
 * @author nexusai
 */
public record AccessKeyQuotaAdjustRequest(BigDecimal quotaUsd) {
}
