package com.y2a.server.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 任务事件监听器（观察者模式）：
 * 把需要异步投递的通知事件落到 notification_outbox，由后台投递器扫描发送。
 * 这样 TaskService 不直接依赖通知渠道，满足开闭原则。
 */
@Component
public class TaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(TaskEventListener.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final com.y2a.server.service.TaskEventStreamService streamService;

    public TaskEventListener(OutboxRepository outboxRepository, ObjectMapper objectMapper,
                             com.y2a.server.service.TaskEventStreamService streamService) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.streamService = streamService;
    }

    @Async
    @EventListener
    public void onTaskEvent(TaskEventPublisher.TaskDomainEvent event) {
        // 广播给 SSE 订阅者（前端任务列表实时刷新）
        try {
            streamService.publish(objectMapper.writeValueAsString(Map.of(
                    "type", event.getEventType(),
                    "taskId", event.getTaskId(),
                    "payload", event.getPayload())));
        } catch (Exception ignored) {
        }
        // 只对以下事件发通知，避免 status_changed 刷屏（阶段4会细化事件订阅配置）
        if (!"task_added".equals(event.getEventType())
                && !"task_completed".equals(event.getEventType())
                && !"task_failed".equals(event.getEventType())) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "taskId", event.getTaskId(),
                    "eventType", event.getEventType(),
                    "details", event.getPayload()
            );
            outboxRepository.enqueue(event.getEventType(),
                    objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("事件入 outbox 失败: taskId={} event={}", event.getTaskId(), event.getEventType(), e);
        }
    }
}
