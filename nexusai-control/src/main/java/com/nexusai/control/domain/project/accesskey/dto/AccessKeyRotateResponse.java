package com.nexusai.control.domain.project.accesskey.dto;

/**
 * 独立 Key 轮转响应。旧 Key 立即失效，新 {@code key} 明文只返回一次（keyHash 内部值不外泄）。
 *
 * @author nexusai
 */
public record AccessKeyRotateResponse(Long id, String key) {
}
