package com.y2a.server.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静态资源一次性票据（用于 &lt;img src&gt; 这类无法带 Authorization 头的场景）。
 *
 * <p>背景：浏览器对 img/script 等资源不会附带自定义请求头，因此需要登录的
 * 图片端点无法通过 JWT 校验。做法是：登录用户调用受保护接口拿到 ticket，
 * 再用 {@code ?ticket=xxx} 访问图片端点，由票据代替身份。</p>
 *
 * <p>安全约束：票据与资源路径绑定、默认 5 分钟过期、可单次使用。</p>
 */
@Service
public class ResourceTicketService {

    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;

    /** ticket → 绑定信息。 */
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    private record Ticket(String resource, long expiresAt, String owner) {
    }

    /** 签发票据。 */
    public String issue(String resource, String owner) {
        cleanup();
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, new Ticket(resource, System.currentTimeMillis() + DEFAULT_TTL_MS, owner));
        return ticket;
    }

    /**
     * 校验票据是否可访问指定资源。
     *
     * @param ticket   票据
     * @param resource 期望访问的资源标识（如 qrcode image 的 sessionId）
     */
    public boolean validate(String ticket, String resource) {
        if (ticket == null || ticket.isBlank()) return false;
        Ticket t = tickets.get(ticket);
        if (t == null) return false;
        if (System.currentTimeMillis() > t.expiresAt()) {
            tickets.remove(ticket);
            return false;
        }
        return resource == null || resource.equals(t.resource());
    }

    /** 单次使用：校验通过后立即作废。 */
    public boolean consume(String ticket, String resource) {
        boolean ok = validate(ticket, resource);
        if (ok) tickets.remove(ticket);
        return ok;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }
}
