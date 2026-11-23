package com.y2a.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元信息翻译器（对齐 Python ai_enhancer.translate_video_metadata）。
 *
 * <p>流程：
 * <ol>
 *   <li>输入清洗（去导流/压空行/标题单行化）</li>
 *   <li>首轮翻译（标题 + 简介一起）</li>
 *   <li>逐字段校验（空/导流/过于相似/非自然简介）</li>
 *   <li>失败字段单独重试（标题重试 / 简介重试用专用提示词）</li>
 *   <li>仍失败则回退原文（清洗后）并截断到平台限额</li>
 * </ol>
 * </p>
 */
@Component
public class MetadataTranslator {

    private static final Logger log = LoggerFactory.getLogger(MetadataTranslator.class);

    private final AiClient aiClient;
    private final PromptTemplates prompts;

    public MetadataTranslator(AiClient aiClient, PromptTemplates prompts) {
        this.aiClient = aiClient;
        this.prompts = prompts;
    }

    /** 翻译结果。 */
    public record Result(String title, String description,
                         boolean titleTranslated, boolean descriptionTranslated,
                         List<String> warnings) {
        public boolean anyTranslated() {
            return titleTranslated || descriptionTranslated;
        }
    }

    /**
     * 翻译标题与简介。
     *
     * @param title         原始标题
     * @param description   原始简介
     * @param targetLanguage 目标语言（zh/en/ja/ko…）
     * @param titleLimit    平台标题上限
     * @param descLimit     平台简介上限
     * @param translateTitle 是否翻译标题
     * @param translateDesc  是否翻译简介
     */
    public Result translate(String title, String description, String targetLanguage,
                            int titleLimit, int descLimit,
                            boolean translateTitle, boolean translateDesc) {
        List<String> warnings = new ArrayList<>();

        String cleanTitle = TextSanitizer.preClean(title, "title", 1);
        String cleanDesc = TextSanitizer.preClean(description, "description", 2);

        boolean titleUsable = translateTitle
                && TextSanitizer.hasMeaningfulContent(cleanTitle, "title");
        boolean descUsable = translateDesc
                && TextSanitizer.hasMeaningfulContent(cleanDesc, "description");

        if (!titleUsable && translateTitle) {
            warnings.add("标题清洗后无实质内容，保留原文");
        }
        if (!descUsable && translateDesc) {
            warnings.add("简介清洗后无实质内容，保留原文");
        }

        String outTitle = titleUsable ? "" : TextSanitizer.applyLimits(cleanTitle, "title",
                titleLimit, descLimit);
        String outDesc = descUsable ? "" : TextSanitizer.applyLimits(cleanDesc, "description",
                titleLimit, descLimit);
        boolean titleDone = false;
        boolean descDone = false;

        if (!titleUsable && !descUsable) {
            return new Result(outTitle, outDesc, false, false, warnings);
        }

        AiClient.AiConfig cfg = aiClient.globalConfig();
        if (!cfg.usable()) {
            warnings.add("AI 未配置，跳过翻译");
            return new Result(outTitle, outDesc, false, false, warnings);
        }

        // ---------- 首轮：标题 + 简介 ----------
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target_language", targetLanguage);
        if (titleUsable) payload.put("title", cleanTitle);
        if (descUsable) payload.put("description", cleanDesc);

        JsonNode first = requestJson(cfg, prompts.metadataTranslate(targetLanguage, false),
                payload, "metadata_translate", warnings);

        if (titleUsable) {
            String candidate = TextSanitizer.applyLimits(
                    text(first, "title"), "title", titleLimit, descLimit);
            var v = TextSanitizer.validateOutput(cleanTitle, candidate, "title", 2);
            if (v.passed() && !candidate.isBlank()) {
                outTitle = candidate;
                titleDone = true;
            } else {
                warnings.add("标题首轮校验未通过: " + v.summary());
            }
        }
        if (descUsable) {
            String candidate = TextSanitizer.applyLimits(
                    text(first, "description"), "description", titleLimit, descLimit);
            var v = TextSanitizer.validateOutput(cleanDesc, candidate, "description", 2);
            if (v.passed() && !candidate.isBlank()) {
                outDesc = candidate;
                descDone = true;
            } else {
                warnings.add("简介首轮校验未通过: " + v.summary());
            }
        }

        // ---------- 标题重试（单独）----------
        if (titleUsable && !titleDone) {
            Map<String, Object> retryPayload = new LinkedHashMap<>();
            retryPayload.put("target_language", targetLanguage);
            retryPayload.put("title", cleanTitle);
            JsonNode retry = requestJson(cfg, prompts.metadataTranslate(targetLanguage, true),
                    retryPayload, "metadata_title_retry", warnings);
            String candidate = TextSanitizer.applyLimits(
                    text(retry, "title"), "title", titleLimit, descLimit);
            var v = TextSanitizer.validateOutput(cleanTitle, candidate, "title", 2);
            if (v.passed() && !candidate.isBlank()) {
                outTitle = candidate;
                titleDone = true;
            } else {
                warnings.add("标题重试仍失败，回退原文");
                outTitle = TextSanitizer.applyLimits(cleanTitle, "title", titleLimit, descLimit);
            }
        }

        // ---------- 简介重试（专用提示词）----------
        if (descUsable && !descDone) {
            Map<String, Object> retryPayload = new LinkedHashMap<>();
            retryPayload.put("target_language", targetLanguage);
            retryPayload.put("description", cleanDesc);
            JsonNode retry = requestJson(cfg, prompts.metadataDescRetry(targetLanguage),
                    retryPayload, "metadata_desc_retry", warnings);
            String candidate = TextSanitizer.applyLimits(
                    text(retry, "description"), "description", titleLimit, descLimit);
            var v = TextSanitizer.validateOutput(cleanDesc, candidate, "description", 2);
            if (v.passed() && !candidate.isBlank()) {
                outDesc = candidate;
                descDone = true;
            } else {
                warnings.add("简介重试仍失败，回退原文");
                outDesc = TextSanitizer.applyLimits(cleanDesc, "description", titleLimit, descLimit);
            }
        }

        return new Result(outTitle, outDesc, titleDone, descDone, warnings);
    }

    private JsonNode requestJson(AiClient.AiConfig cfg, String systemPrompt,
                                 Map<String, Object> payload, String scene,
                                 List<String> warnings) {
        try {
            String userContent = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(payload);
            return aiClient.chatJson(cfg, systemPrompt, userContent, 0.3, scene);
        } catch (Exception e) {
            log.warn("[{}] 请求失败: {}", scene, e.getMessage());
            warnings.add(scene + " 请求失败: " + e.getMessage());
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
