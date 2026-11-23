package com.y2a.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签生成器（对齐 Python ai_enhancer.generate_acfun_tags）。
 *
 * <p>要点：
 * <ul>
 *   <li>输入清洗后若无实质内容直接跳过</li>
 *   <li>预设标签作为「禁止复用」清单拼进提示词，并在解析后二次过滤</li>
 *   <li>每个标签截断到 10 字（AcFun 上限），最多 6 个</li>
 *   <li>失败返回空列表，不阻塞主流程</li>
 * </ul>
 * </p>
 */
@Component
public class TagGenerator {

    private static final Logger log = LoggerFactory.getLogger(TagGenerator.class);

    /** 标签数量上限（AcFun 口径；bilibili 由上传器再截断）。 */
    private static final int MAX_TAGS = 6;
    /** 单标签字数上限。 */
    private static final int MAX_TAG_LEN = 10;
    /** 简介送模型的最大长度。 */
    private static final int DESC_INPUT_LIMIT = 200;

    private final AiClient aiClient;
    private final PromptTemplates prompts;

    public TagGenerator(AiClient aiClient, PromptTemplates prompts) {
        this.aiClient = aiClient;
        this.prompts = prompts;
    }

    /**
     * 生成标签。
     *
     * @param title      标题（可为译文）
     * @param description 简介（可为译文）
     * @param avoidTags  预设标签（禁止复用）
     * @param taskId     任务 ID（仅日志）
     */
    public List<String> generate(String title, String description,
                                 List<String> avoidTags, String taskId) {
        String cleanTitle = TextSanitizer.preClean(title, "title", 1);
        String cleanDesc = TextSanitizer.preClean(description, "description", 2);

        if (!TextSanitizer.hasMeaningfulContent(cleanTitle, "title")) cleanTitle = "";
        if (!TextSanitizer.hasMeaningfulContent(cleanDesc, "description")) cleanDesc = "";

        if (cleanTitle.isEmpty() && cleanDesc.isEmpty()) {
            log.info("[{}] 标签生成跳过：标题与简介均无实质内容", taskId);
            return List.of();
        }

        AiClient.AiConfig cfg = aiClient.globalConfig();
        if (!cfg.usable()) {
            log.info("[{}] 标签生成跳过：AI 未配置", taskId);
            return List.of();
        }

        // 预设标签处理：既用于提示词，也用于解析后过滤
        List<String> promptAvoid = new ArrayList<>();
        Set<String> avoidSet = new LinkedHashSet<>();
        for (String raw : avoidTags == null ? List.<String>of() : avoidTags) {
            String tag = TextSanitizer.normalizeWhitespace(raw);
            if (tag.isEmpty()) continue;
            // 截断到 10 字形式一并纳入比对，避免与模型输出撞车
            String truncated = tag.length() > MAX_TAG_LEN ? tag.substring(0, MAX_TAG_LEN) : tag;
            avoidSet.add(truncated.toLowerCase());
            avoidSet.add(tag.toLowerCase());
            if (promptAvoid.size() < 20) {
                // 提示词里按 20 字收口（bilibili 上限），避免过长占额度
                promptAvoid.add(tag.length() > 20 ? tag.substring(0, 20) : tag);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", cleanTitle);
        payload.put("description", cleanDesc.length() > DESC_INPUT_LIMIT
                ? cleanDesc.substring(0, DESC_INPUT_LIMIT) : cleanDesc);

        JsonNode parsed;
        try {
            String userContent = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(payload);
            parsed = aiClient.chatJson(cfg, prompts.tags(promptAvoid), userContent,
                    0.2, "ai_tags");
        } catch (Exception e) {
            log.warn("[{}] 标签生成失败: {}", taskId, e.getMessage());
            return List.of();
        }

        JsonNode rawTags = parsed == null ? null : parsed.get("tags");
        if (rawTags == null || !rawTags.isArray()) {
            log.warn("[{}] 标签响应缺少 tags 数组", taskId);
            return List.of();
        }

        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : rawTags) {
            if (!node.isTextual()) continue;
            String tag = TextSanitizer.normalizeWhitespace(node.asText());
            if (tag.isEmpty()) continue;
            if (tag.length() > MAX_TAG_LEN) tag = tag.substring(0, MAX_TAG_LEN);
            String lower = tag.toLowerCase();
            if (seen.contains(lower) || avoidSet.contains(lower)) continue;
            seen.add(lower);
            result.add(tag);
            if (result.size() >= MAX_TAGS) break;
        }

        log.info("[{}] 生成标签 {} 个: {}", taskId, result.size(), result);
        return result;
    }
}
