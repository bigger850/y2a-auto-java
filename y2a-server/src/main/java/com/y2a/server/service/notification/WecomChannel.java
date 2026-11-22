package com.y2a.server.service.notification;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** 企业微信机器人渠道：优先 Markdown，失败回退纯文本。 */
@Component
public class WecomChannel implements NotificationChannel {

    @Override
    public String id() {
        return "wecom";
    }

    @Override
    public boolean send(String title, String content, Map<String, Object> config) {
        String webhook = str(config.get("NOTIFY_WECOM_WEBHOOK_URL"));
        if (webhook.isBlank()) {
            return false;
        }
        RestClient client = RestClient.create();
        String markdown = """
                {"msgtype":"markdown","markdown":{"content":"**%s**\\n%s"}}
                """.formatted(escape(title), escape(content));
        try {
            var resp = client.post().uri(webhook)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(markdown).retrieve().toEntity(String.class);
            if (resp.getStatusCode().is2xxSuccessful() && !resp.getBody().contains("\"errcode\":0")) {
                // errcode 非 0 时回退纯文本
                String text = """
                        {"msgtype":"text","text":{"content":"%s\\n%s"}}
                        """.formatted(escape(title), escape(content));
                client.post().uri(webhook)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(text).retrieve().toBodilessEntity();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
