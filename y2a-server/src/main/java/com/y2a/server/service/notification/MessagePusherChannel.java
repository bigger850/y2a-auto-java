package com.y2a.server.service.notification;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** message-pusher 渠道。 */
@Component
public class MessagePusherChannel implements NotificationChannel {

    @Override
    public String id() {
        return "message_pusher";
    }

    @Override
    public boolean send(String title, String content, Map<String, Object> config) {
        String server = str(config.get("NOTIFY_MESSAGE_PUSHER_SERVER"));
        String username = str(config.get("NOTIFY_MESSAGE_PUSHER_USERNAME"));
        String token = str(config.get("NOTIFY_MESSAGE_PUSHER_TOKEN"));
        String channel = str(config.get("NOTIFY_MESSAGE_PUSHER_CHANNEL"));
        if (server.isBlank() || token.isBlank()) {
            return false;
        }
        String url = server.replaceAll("/$", "") + "/message/push/"
                + (channel.isBlank() ? "" : channel + "/");
        try {
            RestClient.create().post().uri(url)
                    .header("Authorization", "Bearer " + token)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("""
                            {"title":"%s","content":"%s","username":"%s"}
                            """.formatted(esc(title), esc(content), esc(username)))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
