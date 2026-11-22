package com.y2a.server.service;

import com.y2a.server.event.TaskEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 任务事件流（SSE）广播器（观察者模式）。
 * 前端 /api/tasks/stream 订阅；任务事件通过 {@link #publish(String)} 广播给所有订阅者。
 */
@Service
public class TaskEventStreamService {

    private final List<Consumer<String>> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<String> subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Consumer<String> subscriber) {
        subscribers.remove(subscriber);
    }

    public void publish(String event) {
        for (Consumer<String> s : subscribers) {
            try {
                s.accept(event);
            } catch (Exception ignored) {
                // 单个订阅者失败不影响其它订阅者
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
