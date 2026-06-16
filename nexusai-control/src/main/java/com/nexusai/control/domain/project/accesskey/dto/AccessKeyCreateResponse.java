package com.nexusai.control.domain.project.accesskey.dto;

/**
 * 独立 Key 创建响应。{@code key} 明文只返回一次；服务端仅保存其 SHA-256（keyHash 内部值不外泄）。
 *
 * @author nexusai
 */
public record AccessKeyCreateResponse(Long id, String key, String name) {
}
