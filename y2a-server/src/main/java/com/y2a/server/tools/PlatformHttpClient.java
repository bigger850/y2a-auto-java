package com.y2a.server.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 平台 HTTP 客户端（Cookie 注入 + 表单/JSON + 文件上传）。
 *
 * <p>统一处理 AcFun / bilibili 等平台的上传请求：Cookie 读取（Netscape 或 JSON）、
 * 表单编码、multipart、超时与重试。所有平台上传器复用此客户端，避免各处重复造轮子。</p>
 */
@Component
public class PlatformHttpClient {

    private final HttpClient client;
    private final ObjectMapper mapper;

    public PlatformHttpClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 一次平台响应。 */
    public record Response(int status, String body, Map<String, String> headers) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (Exception e) {
                return new ObjectMapper().createObjectNode();
            }
        }
    }

    // ---------------- Cookie 解析 ----------------

    /**
     * 解析 Cookie 文件为 (name → value)。
     * 支持 Netscape cookies.txt 与 JSON（浏览器导出 / Playwright storage_state）。
     */
    public Map<String, String> loadCookies(Path cookieFile) {
        Map<String, String> result = new LinkedHashMap<>();
        if (cookieFile == null || !Files.isRegularFile(cookieFile)) {
            return result;
        }
        try {
            String text = Files.readString(cookieFile, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{")) {
                return parseJsonCookies(text);
            }
            return parseNetscapeCookies(text);
        } catch (Exception e) {
            return result;
        }
    }

    private Map<String, String> parseJsonCookies(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(text);
            // Playwright storage_state: {cookies: [...]}
            JsonNode list = root.has("cookies") ? root.get("cookies") : root;
            if (list.isArray()) {
                for (JsonNode c : list) {
                    String name = c.path("name").asText("");
                    String value = c.path("value").asText("");
                    if (!name.isEmpty()) result.put(name, value);
                }
            } else if (list.isObject()) {
                list.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText("")));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private Map<String, String> parseNetscapeCookies(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\t");
            if (parts.length >= 7) {
                result.put(parts[5], parts[6]);
            }
        }
        return result;
    }

    /** 拼 Cookie 请求头。 */
    public String cookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        cookies.forEach((k, v) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    // ---------------- 请求方法 ----------------

    /** 表单 POST。 */
    public Response postForm(String url, Map<String, String> form, Map<String, String> cookies,
                             Map<String, String> extraHeaders) {
        String body = encodeForm(form);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        applyHeaders(builder, cookies, extraHeaders);
        return send(builder.build());
    }

    /** JSON POST。 */
    public Response postJson(String url, String json, Map<String, String> cookies,
                             Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        applyHeaders(builder, cookies, extraHeaders);
        return send(builder.build());
    }

    /** 原始二进制 POST（分片上传）。 */
    public Response postBytes(String url, byte[] data, Map<String, String> cookies,
                              Map<String, String> extraHeaders, String contentType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(data));
        applyHeaders(builder, cookies, extraHeaders);
        return send(builder.build());
    }

    /** multipart 表单（文件上传）。 */
    public Response postMultipart(String url, Map<String, String> fields, String fileField,
                                  Path file, Map<String, String> cookies,
                                  Map<String, String> extraHeaders) {
        String boundary = "----Y2ABoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload;
        try {
            payload = buildMultipart(boundary, fields, fileField, file);
        } catch (Exception e) {
            return new Response(-1, "构造 multipart 失败: " + e.getMessage(), Map.of());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        applyHeaders(builder, cookies, extraHeaders);
        return send(builder.build());
    }

    /** GET。 */
    public Response get(String url, Map<String, String> cookies, Map<String, String> extraHeaders) {
        return get(url, cookies, extraHeaders, Duration.ofMinutes(2));
    }

    /** 带自定义超时的 GET（用于长轮询接口，超时即视为「无更新」）。 */
    public Response get(String url, Map<String, String> cookies,
                        Map<String, String> extraHeaders, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .GET();
        applyHeaders(builder, cookies, extraHeaders);
        return send(builder.build());
    }

    // ---------------- 内部 ----------------

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> resp = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            resp.headers().map().forEach((k, v) -> headers.put(k, String.join(",", v)));
            return new Response(resp.statusCode(), resp.body(), headers);
        } catch (Exception e) {
            return new Response(-1, "请求失败: " + e.getMessage(), Map.of());
        }
    }

    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> cookies,
                              Map<String, String> extra) {
        builder.header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36");
        builder.header("Accept", "application/json, text/plain, */*");
        if (cookies != null && !cookies.isEmpty()) {
            builder.header("Cookie", cookieHeader(cookies));
        }
        if (extra != null) {
            extra.forEach(builder::header);
        }
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(k)).append('=').append(urlEncode(v == null ? "" : v));
        });
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private byte[] buildMultipart(String boundary, Map<String, String> fields,
                                  String fileField, Path file) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        String CRLF = "\r\n";
        if (fields != null) {
            for (var e : fields.entrySet()) {
                out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"" + CRLF + CRLF)
                        .getBytes(StandardCharsets.UTF_8));
                out.write(String.valueOf(e.getValue()).getBytes(StandardCharsets.UTF_8));
                out.write(CRLF.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (file != null && fileField != null) {
            out.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + file.getFileName() + "\"" + CRLF)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: application/octet-stream" + CRLF + CRLF)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(file));
            out.write(CRLF.getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
