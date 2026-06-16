package com.nexusai.client;

/**
 * 网关控制面调用异常（传输失败 / HTTP 错误 / 网关业务非 200）。
 * <p>{@link #getCode()} 为网关业务码或 HTTP 状态码；传输层失败为 {@code -1}。
 *
 * @author nexusai
 */
public class NexusAiClientException extends RuntimeException {

    private final int code;

    public NexusAiClientException(int code, String message) {
        super(message);
        this.code = code;
    }

    public NexusAiClientException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
