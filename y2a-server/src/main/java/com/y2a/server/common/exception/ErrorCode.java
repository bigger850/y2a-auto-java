package com.y2a.server.common.exception;

/** 统一错误码。 */
public enum ErrorCode {

    OK(0, "ok"),
    BAD_REQUEST(40000, "参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权限"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "状态冲突"),
    INTERNAL(50000, "内部错误"),
    ENGINE_UNAVAILABLE(50100, "处理引擎不可用"),
    INVALID_STATE(50200, "任务状态不允许该操作"),
    LOGIN_LOCKED(50300, "登录失败次数过多，账号已锁定");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
