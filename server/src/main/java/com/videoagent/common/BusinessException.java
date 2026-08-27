package com.videoagent.common;

/**
 * 业务异常：携带统一响应码与提示信息，由全局异常处理器转为 {@link ApiResponse}。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
