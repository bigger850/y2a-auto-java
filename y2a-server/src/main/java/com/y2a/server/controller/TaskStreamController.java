package com.y2a.server.controller;

import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.service.ResourceTicketService;
import com.y2a.server.service.TaskEventStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * SSE 任务事件流（对应 Python /tasks/stream）。
 *
 * <p>浏览器原生 {@code EventSource} 无法携带 Authorization 头，因此不能用 JWT 鉴权。
 * 做法与二维码图片一致：登录用户先调 {@code POST /api/tasks/stream-ticket} 拿一次性票据，
 * 再用 {@code GET /api/tasks/stream?ticket=xxx} 建立长连接（该路径在 SecurityConfig 中 permitAll）。</p>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskStreamController {

    private static final String RESOURCE = "task-stream";

    private final TaskEventStreamService streamService;
    private final ResourceTicketService ticketService;
    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(2);

    public TaskStreamController(TaskEventStreamService streamService,
                                ResourceTicketService ticketService) {
        this.streamService = streamService;
        this.ticketService = ticketService;
    }

    /** 签发 SSE 连接票据（需登录）。 */
    @org.springframework.web.bind.annotation.PostMapping("/stream-ticket")
    public ApiResponse<Map<String, Object>> issueTicket() {
        String ticket = ticketService.issue(RESOURCE, "task-stream");
        return ApiResponse.ok(Map.of("ticket", ticket));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "ticket", required = false) String ticket) {
        // EventSource 不带 Authorization，故用票据校验。
        // 票据无效时抛异常：此时尚未写入响应体，Spring 能正确返回 401/403，
        // 而不是先发 200 再报错（SSE 一旦开始写就无法改状态码）。
        if (!ticketService.validate(ticket, RESOURCE)) {
            throw new com.y2a.server.common.exception.BizException(
                    com.y2a.server.common.exception.ErrorCode.UNAUTHORIZED,
                    "SSE 连接票据无效或已过期，请重新获取");
        }
        SseEmitter emitter = new SseEmitter(0L); // 不过期
        final Consumer<String>[] holder = new Consumer[1];
        Consumer<String> listener = event -> {
            try {
                emitter.send(SseEmitter.event().name("message").data(event));
            } catch (IOException e) {
                streamService.unsubscribe(holder[0]);
            }
        };
        holder[0] = listener;
        streamService.subscribe(listener);
        // 心跳任务句柄：连接结束必须取消，否则每个连接都会泄漏一个定时任务
        final java.util.concurrent.ScheduledFuture<?>[] heartbeatTask = new java.util.concurrent.ScheduledFuture<?>[1];

        Runnable cleanup = () -> {
            streamService.unsubscribe(listener);
            if (heartbeatTask[0] != null) {
                heartbeatTask[0].cancel(false);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 心跳，防止代理断连
        heartbeatTask[0] = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                cleanup.run();
            }
        }, 15, 15, TimeUnit.SECONDS);

        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"status\":\"connected\"}"));
        } catch (IOException e) {
            streamService.unsubscribe(listener);
        }
        return emitter;
    }
}
