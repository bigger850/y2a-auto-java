package com.y2a.server.service.notification;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 通知渠道分发器（策略注册表 + 工厂）。 */
@Component
public class NotificationDispatcher {

    private final Map<String, NotificationChannel> channels;

    public NotificationDispatcher(List<NotificationChannel> channelList) {
        this.channels = channelList.stream()
                .collect(Collectors.toMap(NotificationChannel::id, Function.identity()));
    }

    public NotificationChannel get(String id) {
        return channels.get(id);
    }

    public boolean send(String channelId, String title, String content, Map<String, Object> config) {
        NotificationChannel channel = channels.get(channelId);
        return channel != null && channel.send(title, content, config);
    }

    public java.util.Set<String> availableChannels() {
        return channels.keySet();
    }
}
