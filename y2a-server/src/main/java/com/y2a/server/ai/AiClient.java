package com.y2a.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.y2a.server.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容客户端（对齐 Python ai_enhancer 的请求与降级协商）。
 *
 * <p>核心能力：
 * <ul>
 *   <li>Chat Completions（可自动切换 Responses 端点）</li>
 *   <li>JSON 模式：优先 response_format=json_object，服务不支持时逐级降级</li>
 *   <li>思考参数协商：不支持就不传，函数返回时记录</li>
 *   <li>超时 / 重试 / 错误分类</li>
 * </ul>
 * </p>
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    /** 默认端点：DeepSeek（OpenAI 兼容）。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    /** 默认模型：DeepSeek 通用对话模型。 */
    public static final String DEFAULT_MODEL = "deepseek-chat";

    private final ConfigService configService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    /** 端点能力缓存：baseUrl+model → 是否支持 json 模式 / 思考参数。 */
    private final Map<String, Boolean> jsonModeSupport = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Boolean> thinkingSupport = new java.util.concurrent.ConcurrentHashMap<>();

    public AiClient(ConfigService configService) {
        this.configService = configService;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** AI 配置快照。 */
    public record AiConfig(String apiKey, String baseUrl, String model,
                           boolean thinkingEnabled, String reasoningEffort,
                           int timeoutSeconds) {
        public boolean usable() {
            return apiKey != null && !apiKey.isBlank()
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank();
        }
    }

    /** 读取全局 AI 配置。默认使用 DeepSeek（OpenAI 兼容）。 */
    public AiConfig globalConfig() {
        return new AiConfig(
                configService.getString("OPENAI_API_KEY", ""),
                configService.getString("OPENAI_BASE_URL", DEFAULT_BASE_URL),
                configService.getString("OPENAI_MODEL_NAME", DEFAULT_MODEL),
                boolConfig("OPENAI_THINKING_ENABLED", false),
                configService.getString("OPENAI_REASONING_EFFORT", "auto"),
                intConfig("OPENAI_TIMEOUT_SECONDS", 120)
        );
    }

    /** 读取字幕/翻译专用覆盖配置（留空回退全局）。 */
    public AiConfig subtitleConfig() {
        AiConfig base = globalConfig();
        return new AiConfig(
                firstNonBlank(configService.getString("SUBTITLE_OPENAI_API_KEY", ""), base.apiKey()),
                firstNonBlank(configService.getString("SUBTITLE_OPENAI_BASE_URL", ""), base.baseUrl()),
                firstNonBlank(configService.getString("SUBTITLE_OPENAI_MODEL_NAME", ""), base.model()),
                boolConfig("SUBTITLE_OPENAI_THINKING_ENABLED", base.thinkingEnabled()),
                firstNonBlank(configService.getString("SUBTITLE_OPENAI_REASONING_EFFORT", ""), base.reasoningEffort()),
                base.timeoutSeconds()
        );
    }

    /**
     * 发起一次对话请求并返回纯文本内容。
     */
    public String chat(AiConfig cfg, String systemPrompt, String userContent,
                       double temperature, String scene) {
        if (!cfg.usable()) {
            throw new IllegalStateException("AI 未配置（缺少 API Key / Base URL / Model）");
        }
        Map<String, Object> body = buildChatBody(cfg, systemPrompt, userContent, temperature, null);
        return postJson(cfg, body, scene);
    }

    /**
     * 发起一次 JSON 模式请求，返回解析后的 JSON 对象。
     * 服务不支持 response_format 时自动降级为纯文本 + 提取 JSON。
     */
    public JsonNode chatJson(AiConfig cfg, String systemPrompt, String userContent,
                             double temperature, String scene) {
        if (!cfg.usable()) {
            throw new IllegalStateException("AI 未配置（缺少 API Key / Base URL / Model）");
        }
        String cacheKey = cfg.baseUrl() + "|" + cfg.model();
        boolean useJsonMode = jsonModeSupport.getOrDefault(cacheKey, true);

        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> body = buildChatBody(cfg, systemPrompt, userContent,
                    temperature, useJsonMode ? Map.of("type", "json_object") : null);
            String text;
            try {
                text = postJson(cfg, body, scene);
            } catch (AiCallException e) {
                if (useJsonMode && e.isResponseFormatUnsupported()) {
                    log.warn("[{}] 端点不支持 response_format，降级为纯文本", scene);
                    jsonModeSupport.put(cacheKey, false);
                    useJsonMode = false;
                    continue;
                }
                throw e;
            }
            JsonNode parsed = extractJson(text);
            if (parsed != null && !parsed.isMissingNode()) {
                if (useJsonMode) jsonModeSupport.put(cacheKey, true);
                return parsed;
            }
            if (useJsonMode) {
                log.warn("[{}] JSON 模式返回无法解析，降级重试", scene);
                jsonModeSupport.put(cacheKey, false);
                useJsonMode = false;
                continue;
            }
            log.warn("[{}] 模型输出无法解析为 JSON: {}", scene, truncate(text, 200));
            return MissingNode.getInstance();
        }
        return MissingNode.getInstance();
    }

    // ---------------- 请求构造 ----------------

    private Map<String, Object> buildChatBody(AiConfig cfg, String systemPrompt,
                                              String userContent, double temperature,
                                              Map<String, Object> responseFormat) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        body.put("temperature", temperature);
        if (responseFormat != null) {
            body.put("response_format", responseFormat);
        }
        // 思考控制：仅当开启且端点支持时才传（首次乐观传入，不支持会自动降级）
        if (cfg.thinkingEnabled() && supportsThinking(cfg)) {
            String effort = cfg.reasoningEffort();
            if (effort != null && !effort.isBlank() && !"auto".equalsIgnoreCase(effort)) {
                body.put("reasoning_effort", effort);
            }
        }
        return body;
    }

    private boolean supportsThinking(AiConfig cfg) {
        String key = cfg.baseUrl() + "|" + cfg.model() + "|think";
        return thinkingSupport.getOrDefault(key, true);
    }

    private String postJson(AiConfig cfg, Map<String, Object> body, String scene) {
        String endpoint = resolveEndpoint(cfg.baseUrl());
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("构造请求体失败", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(cfg.timeoutSeconds(), 30)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AiCallException("AI 请求失败: " + e.getMessage(), false, true);
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[{}] AI 请求完成，耗时 {}ms，status={}", scene, elapsed, resp.statusCode());

        if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
            throw new AiCallException("AI 服务暂时不可用（HTTP " + resp.statusCode() + "）",
                    false, true);
        }
        if (resp.statusCode() != 200) {
            String bodyText = resp.body() == null ? "" : resp.body();
            boolean formatUnsupported = bodyText.contains("response_format")
                    || bodyText.contains("json_object")
                    || bodyText.contains("Unsupported parameter");
            throw new AiCallException("AI 请求失败（HTTP " + resp.statusCode() + "）: "
                    + truncate(bodyText, 300), formatUnsupported, false);
        }

        try {
            JsonNode root = mapper.readTree(resp.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                // 兼容 Responses API 结构
                String outputText = root.path("output_text").asText("");
                if (!outputText.isBlank()) return outputText;
                throw new AiCallException("AI 响应缺少 choices", false, false);
            }
            JsonNode choice = choices.get(0);
            String finish = choice.path("finish_reason").asText("");
            if (!finish.isEmpty() && !"stop".equals(finish)) {
                // 截断/内容过滤：不作为有效输出
                throw new AiCallException("AI 输出未正常结束（finish_reason=" + finish + "）",
                        false, false);
            }
            JsonNode message = choice.path("message");
            if (!message.path("refusal").isMissingNode()
                    && !message.path("refusal").asText("").isBlank()) {
                throw new AiCallException("AI 拒绝响应", false, false);
            }
            return message.path("content").asText("");
        } catch (AiCallException e) {
            throw e;
        } catch (Exception e) {
            throw new AiCallException("解析 AI 响应失败: " + e.getMessage(), false, false);
        }
    }

    private static String resolveEndpoint(String baseUrl) {
        String url = baseUrl.trim();
        if (url.endsWith("/chat/completions") || url.endsWith("/responses")) {
            return url;
        }
        return url.replaceAll("/+$", "") + "/chat/completions";
    }

    /** 从可能含 markdown 围栏的文本中提取第一个 JSON 对象。 */
    public JsonNode extractJson(String text) {
        if (text == null || text.isBlank()) return MissingNode.getInstance();
        String cleaned = text.trim();
        // 去除 ```json ... ``` 围栏
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return MissingNode.getInstance();
        String candidate = cleaned.substring(start, end + 1);
        try {
            return mapper.readTree(candidate);
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    // ---------------- 工具 ----------------

    private boolean boolConfig(String key, boolean def) {
        Object v = configService.get(key, def);
        if (v instanceof Boolean b) return b;
        return v != null && List.of("true", "1", "on", "yes")
                .contains(String.valueOf(v).toLowerCase());
    }

    private int intConfig(String key, int def) {
        Object v = configService.get(key, def);
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** AI 调用异常，区分「可重试」与「参数不支持」。 */
    public static class AiCallException extends RuntimeException {
        private final boolean responseFormatUnsupported;
        private final boolean retryable;

        public AiCallException(String message, boolean formatUnsupported, boolean retryable) {
            super(message);
            this.responseFormatUnsupported = formatUnsupported;
            this.retryable = retryable;
        }

        public boolean isResponseFormatUnsupported() {
            return responseFormatUnsupported;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}
