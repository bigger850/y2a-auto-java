package com.y2a.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 认证服务：密码校验 + 暴力破解锁定（与 Python 版 LOGIN_MAX_FAILED_ATTEMPTS / LOCKOUT 对齐）。
 * 阶段 1 用配置里的初始密码 + 内存计数；阶段 4 改为从 app_config 读密码与锁定参数。
 */
@Service
public class AuthService {

    private final String initialPassword;
    private final int maxFailedAttempts;
    private final long lockoutSeconds;
    private final AtomicInteger failedAttempts = new AtomicInteger();
    private volatile Instant lockedUntil = Instant.EPOCH;

    public AuthService(@Value("${security.password.initial}") String initialPassword,
                       @Value("${security.login.max-failed:5}") int maxFailedAttempts,
                       @Value("${security.login.lockout-seconds:900}") long lockoutSeconds) {
        this.initialPassword = initialPassword;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutSeconds = lockoutSeconds;
    }

    /** 原版只需密码；username 留空时视为管理员。 */
    public boolean authenticate(String username, String password) {
        if (isLocked()) {
            return false;
        }
        boolean ok = password != null && !password.isBlank() && initialPassword.equals(password);
        if (!ok) {
            if (failedAttempts.incrementAndGet() >= maxFailedAttempts) {
                lockedUntil = Instant.now().plusSeconds(lockoutSeconds);
            }
        }
        return ok;
    }

    public boolean isLocked() {
        return Instant.now().isBefore(lockedUntil);
    }

    public void clearFailures() {
        failedAttempts.set(0);
        lockedUntil = Instant.EPOCH;
    }
}
