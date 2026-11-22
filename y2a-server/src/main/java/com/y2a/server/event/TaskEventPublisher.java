package com.y2a.server.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 领域事件发布器（观察者模式）：任务状态变化、任务增删等事件由此发出。 */
@Component
public class TaskEventPublisher {

    private final ApplicationEventPublisher delegate;

    public TaskEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    public void published(String taskId, String eventType, Map<String, Object> payload) {
        delegate.publishEvent(new TaskDomainEvent(this, taskId, eventType, payload));
    }

    /** 领域事件对象。 */
    public static final class TaskDomainEvent {
        private final Object source;
        private final String taskId;
        private final String eventType;
        private final Map<String, Object> payload;

        public TaskDomainEvent(Object source, String taskId, String eventType, Map<String, Object> payload) {
            this.source = source;
            this.taskId = taskId;
            this.eventType = eventType;
            this.payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        public Object getSource() { return source; }
        public String getTaskId() { return taskId; }
        public String getEventType() { return eventType; }
        public Map<String, Object> getPayload() { return payload; }
    }
}
