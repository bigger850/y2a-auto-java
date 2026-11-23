package com.y2a.server.controller;

import com.y2a.server.ai.AiClient;
import com.y2a.server.ai.PartitionRecommender;
import com.y2a.server.ai.TagGenerator;
import com.y2a.server.common.exception.ApiResponse;
import com.y2a.server.common.exception.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 诊断端点：验证配置、探测连通性、展示分区候选数量。
 * 用于设置页「测试 AI 连接」与排障。
 */
@RestController
@RequestMapping("/api/ai")
public class AiDiagnosticsController {

    private final AiClient aiClient;
    private final PartitionRecommender partitionRecommender;
    private final TagGenerator tagGenerator;

    public AiDiagnosticsController(AiClient aiClient,
                                   PartitionRecommender partitionRecommender,
                                   TagGenerator tagGenerator) {
        this.aiClient = aiClient;
        this.partitionRecommender = partitionRecommender;
        this.tagGenerator = tagGenerator;
    }

    /** 当前 AI 配置快照（不返回密钥明文）。 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        AiClient.AiConfig cfg = aiClient.globalConfig();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", cfg.usable());
        data.put("baseUrl", cfg.baseUrl());
        data.put("model", cfg.model());
        data.put("apiKeyPresent", cfg.apiKey() != null && !cfg.apiKey().isBlank());
        data.put("thinkingEnabled", cfg.thinkingEnabled());
        data.put("reasoningEffort", cfg.reasoningEffort());
        data.put("timeoutSeconds", cfg.timeoutSeconds());
        data.put("acfunPartitions", partitionRecommender.candidatesFor("acfun").size());
        data.put("bilibiliPartitions", partitionRecommender.candidatesFor("bilibili").size());
        return ApiResponse.ok(data);
    }

    /** 连通性测试：发一个最小请求验证端点/密钥可用。 */
    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> test() {
        AiClient.AiConfig cfg = aiClient.globalConfig();
        if (!cfg.usable()) {
            throw BizException.badRequest("AI 未配置：请在设置页填写 Base URL / API Key / 模型名");
        }
        long start = System.currentTimeMillis();
        try {
            String reply = aiClient.chat(cfg,
                    "你是一个连通性测试助手。只回复两个字：正常。",
                    "测试", 0.0, "ai_connectivity_test");
            long elapsed = System.currentTimeMillis() - start;
            return ApiResponse.ok(Map.of(
                    "ok", true,
                    "elapsedMs", elapsed,
                    "model", cfg.model(),
                    "reply", reply == null ? "" : reply.trim()));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            throw new BizException(com.y2a.server.common.exception.ErrorCode.ENGINE_UNAVAILABLE,
                    "AI 连接失败（" + elapsed + "ms）: " + e.getMessage());
        }
    }

    /** 用给定文本试跑标签生成（不影响任务）。 */
    @PostMapping("/preview-tags")
    public ApiResponse<Map<String, Object>> previewTags(
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String title = str(body.get("title"));
        String description = str(body.get("description"));
        if (title.isBlank() && description.isBlank()) {
            throw BizException.badRequest("title 与 description 至少提供一个");
        }
        List<String> tags = tagGenerator.generate(title, description, List.of(), "preview");
        return ApiResponse.ok(Map.of("tags", tags));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
