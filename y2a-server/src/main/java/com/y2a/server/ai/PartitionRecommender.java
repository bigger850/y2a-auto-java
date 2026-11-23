package com.y2a.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * 分区推荐器（对齐 Python ai_enhancer._recommend_partition_core）。
 *
 * <p>从本地 JSON 加载 AcFun / bilibili 分区树，展平为候选列表交给模型选择，
 * 并对返回的 id 做白名单校验（防止模型编造 ID）。</p>
 */
@Component
public class PartitionRecommender {

    private static final Logger log = LoggerFactory.getLogger(PartitionRecommender.class);

    private final AiClient aiClient;
    private final PromptTemplates prompts;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 缓存：AcFun 分区 / bilibili 分区。 */
    private volatile List<Partition> acfunPartitions;
    private volatile List<Partition> bilibiliPartitions;

    public PartitionRecommender(AiClient aiClient, PromptTemplates prompts) {
        this.aiClient = aiClient;
        this.prompts = prompts;
    }

    /** 分区候选。 */
    public record Partition(String id, String name, String description, String parent) {
    }

    /** 推荐结果。 */
    public record Selection(String platform, String id, String name,
                            double confidence, List<String> alternatives,
                            String reason, boolean lowConfidence) {
        public boolean found() {
            return id != null && !id.isBlank();
        }
    }

    /**
     * 为单个平台推荐分区。
     *
     * @param platform  acfun / bilibili
     * @param title     标题（优先译文）
     * @param description 简介（优先译文）
     * @param tags      标签
     */
    public Selection recommend(String platform, String title, String description,
                               List<String> tags) {
        AiClient.AiConfig cfg = aiClient.globalConfig();
        if (!cfg.usable()) {
            return new Selection(platform, null, null, 0, List.of(), "AI 未配置", true);
        }
        List<Partition> candidates = candidatesFor(platform);
        if (candidates.isEmpty()) {
            return new Selection(platform, null, null, 0, List.of(),
                    "分区数据加载失败", true);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_metadata", Map.of(
                "title", title == null ? "" : title,
                "description", truncate(description, 1200),
                "tags", tags == null ? List.of() : tags));
        payload.put("content_profile", Map.of("domain", "unknown"));
        payload.put("platforms", Map.of(platform, candidates.stream()
                .map(p -> Map.of(
                        "id", p.id(),
                        "name", p.name(),
                        "description", truncate(p.description(), 120)))
                .toList()));

        JsonNode parsed;
        try {
            String userContent = mapper.writeValueAsString(payload);
            parsed = aiClient.chatJson(cfg, prompts.partition(), userContent,
                    0.0, "ai_partition_" + platform);
        } catch (Exception e) {
            log.warn("分区推荐请求失败 platform={}: {}", platform, e.getMessage());
            return new Selection(platform, null, null, 0, List.of(),
                    "请求失败: " + e.getMessage(), true);
        }

        // 兼容：单平台时模型可能直接返回 {id,confidence}
        JsonNode node = parsed.get(platform);
        if ((node == null || node.isMissingNode()) && parsed.has("id")) {
            node = parsed;
        }
        if (node == null || node.isMissingNode()) {
            return new Selection(platform, null, null, 0, List.of(),
                    "模型未返回该平台结果", true);
        }

        String rawId = node.path("id").asText("").trim();
        // 白名单校验：防模型编造 ID
        Set<String> validIds = new HashSet<>();
        for (Partition p : candidates) validIds.add(p.id());
        final String id = (!rawId.isEmpty() && validIds.contains(rawId)) ? rawId : "";
        if (!rawId.isEmpty() && id.isEmpty()) {
            log.warn("分区推荐返回非法 ID: {} (platform={})", rawId, platform);
        }

        String name = candidates.stream()
                .filter(p -> p.id().equals(id))
                .map(Partition::name)
                .findFirst().orElse(null);

        double confidence = node.path("confidence").asDouble(0);
        List<String> alternatives = new ArrayList<>();
        for (JsonNode alt : node.path("alternatives")) {
            String altId = alt.asText("").trim();
            if (!altId.isEmpty() && validIds.contains(altId) && !altId.equals(id)) {
                alternatives.add(altId);
                if (alternatives.size() >= 3) break;
            }
        }
        String reason = node.path("reason_summary").asText("");
        boolean lowConfidence = confidence < 0.5 || id.isEmpty();

        return new Selection(platform, id.isEmpty() ? null : id, name,
                confidence, alternatives, reason, lowConfidence);
    }

    /** 当前平台的全部候选分区（供前端下拉与校验）。 */
    public List<Partition> candidatesFor(String platform) {
        if ("acfun".equalsIgnoreCase(platform)) {
            if (acfunPartitions == null) acfunPartitions = loadAcfun();
            return acfunPartitions;
        }
        if ("bilibili".equalsIgnoreCase(platform)) {
            if (bilibiliPartitions == null) bilibiliPartitions = loadBilibili();
            return bilibiliPartitions;
        }
        return List.of();
    }

    // ---------------- 数据加载 ----------------

    private List<Partition> loadAcfun() {
        List<Partition> result = new ArrayList<>();
        try (InputStream in = new ClassPathResource("partitions/acfun_id_mapping.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            for (JsonNode category : root) {
                String categoryName = category.path("category").asText("");
                for (JsonNode p : category.path("partitions")) {
                    result.add(new Partition(
                            p.path("id").asText(""),
                            p.path("name").asText(""),
                            p.path("description").asText(""),
                            categoryName));
                    for (JsonNode sub : p.path("sub_partitions")) {
                        result.add(new Partition(
                                sub.path("id").asText(""),
                                sub.path("name").asText(""),
                                sub.path("description").asText(""),
                                categoryName + "/" + p.path("name").asText("")));
                    }
                }
            }
            log.info("已加载 AcFun 分区 {} 个", result.size());
        } catch (Exception e) {
            log.error("加载 AcFun 分区失败", e);
        }
        return result;
    }

    private List<Partition> loadBilibili() {
        List<Partition> result = new ArrayList<>();
        try (InputStream in = new ClassPathResource("partitions/bilibili_zones.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            for (JsonNode zone : root) {
                String tid = zone.path("tid").asText("");
                String name = zone.path("name").asText("");
                if (tid.isEmpty() || "0".equals(tid)) continue;
                result.add(new Partition(tid, name, "", ""));
                for (JsonNode sub : zone.path("sub")) {
                    String subTid = sub.path("tid").asText("");
                    if (subTid.isEmpty()) continue;
                    result.add(new Partition(subTid, sub.path("name").asText(""), "", name));
                }
            }
            log.info("已加载 bilibili 分区 {} 个", result.size());
        } catch (Exception e) {
            log.error("加载 bilibili 分区失败", e);
        }
        return result;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
