package com.y2a.server.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/** 全局异常处理器：所有异常统一转成 ApiResponse。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        log.warn("业务异常: {} - {}", e.errorCode(), e.getMessage());
        return ResponseEntity.status(status(e.errorCode()))
                .body(ApiResponse.fail(e.errorCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception e) {
        String msg = e.getMessage() == null ? "参数校验失败" : e.getMessage();
        return ResponseEntity.status(status(ErrorCode.BAD_REQUEST))
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, msg));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(status(ErrorCode.FORBIDDEN))
                .body(ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e, HttpServletRequest req) {
        String traceId = UUID.randomUUID().toString();
        log.error("未处理异常 traceId={} method={} uri={}", traceId, req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(status(ErrorCode.INTERNAL))
                .body(ApiResponse.fail(ErrorCode.INTERNAL, ErrorCode.INTERNAL.message(), traceId));
    }

    private HttpStatus status(ErrorCode error) {
        return switch (error) {
            case UNAUTHORIZED, LOGIN_LOCKED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST, INVALID_STATE -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case ENGINE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
