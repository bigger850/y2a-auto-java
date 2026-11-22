package com.y2a.server.common.exception;

import java.time.Instant;

/**
 * 统一响应体（Builder 模式）。
 *
 * @param <T> 业务数据类型
 */
public final class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;
    private final String traceId;
    private final Instant timestamp;

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = Instant.now();
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data, null);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode error) {
        return new ApiResponse<>(error.code(), error.message(), null, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode error, String message) {
        return new ApiResponse<>(error.code(), message, null, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode error, String message, String traceId) {
        return new ApiResponse<>(error.code(), message, null, traceId);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public boolean isSuccess() {
        return code == ErrorCode.OK.code();
    }
}
