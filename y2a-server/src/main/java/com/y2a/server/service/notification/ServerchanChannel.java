package com.y2a.server.service.notification;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Server酱渠道。 */
@Component
public class ServerchanChannel implements NotificationChannel {

    @Override
    public String id() {
        return "serverchan";
    }

    @Override
    public boolean send(String title, String content, Map<String, Object> config) {
        String sendkey = str(config.get("NOTIFY_SERVERCHAN_SENDKEY"));
        if (sendkey.isBlank()) {
            return false;
        }
        String url = "https://sctapi.ftqq.com/" + sendkey + ".send";
        try {
            RestClient.create().post().uri(uriBuilder -> uriBuilder
                            .scheme("https").host("sctapi.ftqq.com").path("/" + sendkey + ".send")
                            .queryParam("title", title).queryParam("desp", content).build())
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
