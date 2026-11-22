package com.y2a.server.engine;

import com.y2a.server.common.exception.BizException;
import com.y2a.server.engine.dto.EngineTaskRequest;
import com.y2a.server.engine.dto.EngineCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 处理引擎 HTTP 客户端（回调模式）。
 *
 * <p>只负责「提交任务」：Java 把阶段请求 POST 给引擎，立即返回；
 * 引擎执行完通过 {@link #callbackBaseUrl} 回调 Java 的 /api/internal/engine/callback。
 * 这样 Java 不需要轮询长任务，SSE 进度由回调事件驱动。</p>
 *
 * <p>超时/重试/熔断在 {@link #submit} 内统一处理；具体阶段差异由
 * 调用方组装 {@link EngineTaskRequest#getPayload()}（模板方法思想的轻量实现）。</p>
 */
@Component
public class EngineClient {

    private static final Logger log = LoggerFactory.getLogger(EngineClient.class);

    private final WebClient webClient;
    private final String callbackBaseUrl;

    public EngineClient(WebClient.Builder builder,
                        @Value("${engine.base-url}") String baseUrl,
                        @Value("${engine.callback-base-url}") String callbackBaseUrl,
                        @Value("${engine.connect-timeout-ms:5000}") long connectTimeoutMs,
                        @Value("${engine.response-timeout-ms:30000}") long responseTimeoutMs) {
        this.webClient = builder.baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        this.callbackBaseUrl = callbackBaseUrl;
        this.timeoutConnect = Duration.ofMillis(connectTimeoutMs);
        this.timeoutResponse = Duration.ofMillis(responseTimeoutMs);
    }

    private final Duration timeoutConnect;
    private final Duration timeoutResponse;

    /**
     * 提交一个阶段任务给引擎。回调地址自动填充。
     *
     * @return 引擎返回的受理 ID（如 engine-side task id）
     */
    public String submit(String stage, String taskId, Map<String, Object> payload) {
        String callback = callbackBaseUrl + "/api/internal/engine/callback";
        EngineTaskRequest request = EngineTaskRequest.builder()
                .taskId(taskId)
                .stage(stage)
                .payload(payload)
                .callbackUrl(callback)
                .build();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = webClient.post()
                    .uri("/engine/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(timeoutResponse)
                    .block(timeoutConnect.plus(timeoutResponse));
            return resp == null ? "" : String.valueOf(resp.getOrDefault("engineTaskId", ""));
        } catch (Exception e) {
            log.error("提交引擎任务失败: stage={} taskId={}", stage, taskId, e);
            throw BizException.engineUnavailable("引擎不可用: " + e.getMessage());
        }
    }

    /** 查询引擎健康状态（供 /api/system/health 用）。 */
    public boolean healthy() {
        try {
            String s = webClient.get().uri("/engine/health")
                    .retrieve().bodyToMono(String.class)
                    .timeout(timeoutConnect).block();
            return s != null && s.contains("ok");
        } catch (Exception e) {
            return false;
        }
    }
}
