package com.nexusai.common.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应（仅用于 admin 管理 API；relay /v1/* 走 OpenAI 兼容透传不包裹）。
 * 约定：code 200 = 成功、500 = 失败。
 *
 * @author nexusai
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int SUCCESS = 200;
    public static final int FAIL = 500;

    private int code;
    private String msg;
    private T data;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(SUCCESS, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, "ok", data);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(FAIL, msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public static boolean isOk(R<?> r) {
        return r != null && r.code == SUCCESS;
    }
}
