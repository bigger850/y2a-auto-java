package com.y2a.server.service.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.repository.OutboxRepository;
import com.y2a.server.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbox 后台投递器：扫描待投递通知，按配置的渠道发送，失败按递增间隔重试。
 * 重试间隔与 Python 版一致：30s → 120s → 600s → 1800s → 3600s。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final long[] RETRY_SECONDS = {30, 120, 600, 1800, 3600};

    private final OutboxRepository outbox;
    private final NotificationDispatcher dispatcher;
    private final ConfigService configService;
    private final ObjectMapper mapper;

    public OutboxDispatcher(OutboxRepository outbox, NotificationDispatcher dispatcher,
                            ConfigService configService, ObjectMapper mapper) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.configService = configService;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void pump() {
        Map<String, Object> cfg = configService.getAll();
        if (!bool(cfg.get("NOTIFY_ENABLED"))) {
            return;
        }
        List<Map<String, Object>> due = outbox.dueMessages();
        for (Map<String, Object> row : due) {
            long id = ((Number) row.get("id")).longValue();
            int attempts = ((Number) row.get("attempts")).intValue();
            try {
                JsonNode payload = mapper.readTree(String.valueOf(row.get("payload")));
                String title = "Y2A-Auto " + text(payload, "eventType");
                String content = text(payload, "taskId") + " " + text(payload, "message");
                boolean any = false;
                for (String channelId : enabledChannels(cfg)) {
                    any |= dispatcher.send(channelId, title, content, cfg);
                }
                if (any) {
                    outbox.markSent(id);
                } else {
                    scheduleRetry(id, attempts, "no channel succeeded");
                }
            } catch (Exception e) {
                scheduleRetry(id, attempts, e.getMessage());
            }
        }
    }

    private void scheduleRetry(long id, int attempts, String error) {
        int idx = Math.min(attempts, RETRY_SECONDS.length - 1);
        outbox.markFailed(id, attempts + 1,
                Instant.now().plusSeconds(RETRY_SECONDS[idx]),
                error == null ? "" : error.substring(0, Math.min(error.length(), 500)));
    }

    private List<String> enabledChannels(Map<String, Object> cfg) {
        Object raw = cfg.get("NOTIFY_CHANNELS");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        if (bool(cfg.get("NOTIFY_WECOM_ENABLED"))) result.add("wecom");
        if (bool(cfg.get("NOTIFY_SERVERCHAN_ENABLED"))) result.add("serverchan");
        if (bool(cfg.get("NOTIFY_MESSAGE_PUSHER_ENABLED"))) result.add("message_pusher");
        return result;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null ? "" : v.asText();
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && List.of("true", "1", "on", "yes").contains(String.valueOf(o).toLowerCase());
    }
}
