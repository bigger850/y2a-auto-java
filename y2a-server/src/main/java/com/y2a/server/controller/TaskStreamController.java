package com.y2a.server.controller;

import com.y2a.server.service.TaskEventStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** SSE 任务事件流（对应 Python /tasks/stream）。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskStreamController {

    private final TaskEventStreamService streamService;
    private final ScheduledExecutorService heartbeat = Executors.newScheduledThreadPool(2);

    public TaskStreamController(TaskEventStreamService streamService) {
        this.streamService = streamService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
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
        emitter.onCompletion(() -> streamService.unsubscribe(listener));
        emitter.onTimeout(() -> streamService.unsubscribe(listener));

        // 心跳，防止代理断连
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                streamService.unsubscribe(listener);
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
