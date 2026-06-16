package com.nexusai.common.exception;

import lombok.Getter;

import java.io.Serial;

/**
 * 网关统一业务异常（携带 {@link GatewayErrorCode}，relay 全局处理器据此出 OpenAI 兼容 error）。
 *
 * @author nexusai
 */
@Getter
public class GatewayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final GatewayErrorCode errorCode;
    /** 覆盖默认文案（可空） */
    private final String detail;

    public GatewayException(GatewayErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public GatewayException(GatewayErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public GatewayException(GatewayErrorCode errorCode, String detail, Throwable cause) {
        super(detail == null ? errorCode.getMessage() : detail, cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public static GatewayException of(GatewayErrorCode errorCode) {
        return new GatewayException(errorCode);
    }

    public static GatewayException of(GatewayErrorCode errorCode, String detail) {
        return new GatewayException(errorCode, detail);
    }
}
