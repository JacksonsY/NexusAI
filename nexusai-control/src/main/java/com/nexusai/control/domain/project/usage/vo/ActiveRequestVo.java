package com.nexusai.control.domain.project.usage.vo;

/**
 * 进行中请求（/usage/active·进程内注册表快照）。
 * <p>字段名/类型与前端 {@code NexusAI.ActiveRequest} 严格对齐；Long 字段经全局序列化器下发为字符串
 * （与原 Map 行为一致）。
 *
 * @author nexusai
 */
public record ActiveRequestVo(
        String requestId,
        String status,
        String projectCode,
        Long channelId,
        Long keyId,
        String model,
        String targetModel,
        String modality,
        Boolean streamMode,
        String apiFormat,
        String endpointApiFormat,
        String clientIp,
        String userAgent,
        String clientFamily,
        Integer httpStatus,
        Long ttftMs,
        long elapsedMs,
        String createDt) {
}
