package com.videoagent.common;

/**
 * 统一响应体：{ code, message, data }，code = 0 表示成功。
 *
 * @param code    业务码（0=成功，其余为业务错误码，与 HTTP 状态语义对齐）
 * @param message 提示信息
 * @param data    业务数据
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
