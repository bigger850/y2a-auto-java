package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.common.exception.ErrorCode;
import com.y2a.server.common.security.JwtTokenProvider;
import com.y2a.server.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 登录 API（复刻原版：只需管理员密码）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider tokenProvider;

    public AuthController(AuthService authService, JwtTokenProvider tokenProvider) {
        this.authService = authService;
        this.tokenProvider = tokenProvider;
    }

    /** 兼容 username+password 与仅 password 两种形式。 */
    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest req) {
        if (req.password() == null || req.password().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "密码不能为空");
        }
        if (!authService.authenticate(req.username(), req.password())) {
            if (authService.isLocked()) {
                throw new BizException(ErrorCode.LOGIN_LOCKED);
            }
            throw new BizException(ErrorCode.UNAUTHORIZED, "密码错误");
        }
        authService.clearFailures();
        String username = req.username() == null || req.username().isBlank() ? "admin" : req.username();
        var user = org.springframework.security.core.userdetails.User.withUsername(username)
                        .password("").authorities("ROLE_USER").build();
        return ApiResponse.ok(Map.of(
                "token", tokenProvider.generateToken(user),
                "tokenType", "Bearer",
                "message", "登录成功"));
    }

    /** 登出：JWT 无状态，前端清除 token 即可；此端点用于语义完整与事件通知。 */
    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout() {
        return ApiResponse.ok(Map.of("message", "您已成功退出。"));
    }

    /** 前端启动时校验 token 是否仍有效。 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(jakarta.servlet.http.HttpServletRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean loggedIn = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(String.valueOf(auth.getPrincipal()));
        return ApiResponse.ok(Map.of("logged_in", loggedIn,
                "username", loggedIn ? String.valueOf(auth.getPrincipal()) : ""));
    }
}
