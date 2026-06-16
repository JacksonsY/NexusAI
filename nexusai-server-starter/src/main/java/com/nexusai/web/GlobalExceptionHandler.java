package com.nexusai.web;

import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 控制面 API 全局异常处理（control-api/control-domain 的 Mono&lt;R&gt; 端点）：把 {@link GatewayException} 映射为正确 HTTP 状态 + R 错误体，
 * 避免默认 500。relay /v1/* 自行 writeError（OpenAI 兼容错误体），不经此处。
 *
 * @author nexusai
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 请求参数错误统一回 400（而非 500）。 */
    private static final int BAD_REQUEST = HttpStatus.BAD_REQUEST.value();
    /** 兜底内部错误码（仅给真正未归类的异常）。 */
    private static final int INTERNAL_ERROR = HttpStatus.INTERNAL_SERVER_ERROR.value();
    /** 兜底错误文案（对外不暴露内部栈信息）。 */
    private static final String INTERNAL_ERROR_MSG = "内部错误";

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<R<Void>> handleGateway(GatewayException ex) {
        GatewayErrorCode ec = ex.getErrorCode();
        String msg = ex.getMessage() == null ? ec.getMessage() : ex.getMessage();
        return ResponseEntity.status(ec.getHttpStatus()).body(R.fail(ec.getHttpStatus(), msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(BAD_REQUEST).body(R.fail(BAD_REQUEST, ex.getMessage()));
    }

    /** 业务前置条件不满足（如 SMTP 未配置/连接失败）——把原因带给前端。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<R<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(BAD_REQUEST).body(R.fail(BAD_REQUEST, ex.getMessage()));
    }

    /**
     * Spring WebFlux 框架抛出的带状态异常（如 404 未知路由、405 方法不支持、415 媒体类型不支持、
     * 400 请求体绑定失败等，含 {@link org.springframework.web.ErrorResponseException}）。
     * 必须保留框架原始 HTTP 状态，否则会被 {@link #handleOther} 误吞成 500，掩盖真实客户端错误。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R<Void>> handleResponseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason();
        return ResponseEntity.status(status).body(R.fail(status, reason != null ? reason : ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception ex) {
        log.error("控制面 API 内部错误", ex);
        return ResponseEntity.status(INTERNAL_ERROR).body(R.fail(INTERNAL_ERROR, INTERNAL_ERROR_MSG));
    }
}
