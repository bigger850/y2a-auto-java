package com.y2a.server.common.exception;

/** 业务异常，携带错误码。由全局异常处理器转成 ApiResponse。 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static BizException badRequest(String msg) {
        return new BizException(ErrorCode.BAD_REQUEST, msg);
    }

    public static BizException notFound(String msg) {
        return new BizException(ErrorCode.NOT_FOUND, msg);
    }

    public static BizException invalidState(String msg) {
        return new BizException(ErrorCode.INVALID_STATE, msg);
    }

    public static BizException engineUnavailable(String msg) {
        return new BizException(ErrorCode.ENGINE_UNAVAILABLE, msg);
    }
}
