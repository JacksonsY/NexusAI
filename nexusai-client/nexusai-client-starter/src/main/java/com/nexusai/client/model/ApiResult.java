package com.nexusai.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 网关统一响应信封（与网关 {@code com.nexusai.common.model.R} 对齐·code=200 成功）。
 * <p>SDK 侧独立持有，避免依赖网关反应堆。
 *
 * @param <T> data 载荷类型
 * @author nexusai
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiResult<T>(Integer code, String msg, T data) {

    /** 网关成功码（统一为 200）。 */
    public static final int SUCCESS_CODE = 200;

    public boolean ok() {
        return code != null && code == SUCCESS_CODE;
    }
}
