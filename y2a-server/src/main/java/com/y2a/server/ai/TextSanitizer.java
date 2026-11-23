package com.y2a.server.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本清洗与输出校验（对齐 Python ai_enhancer 的 pre_clean / post_clean / validate_output）。
 *
 * <p>目标：把原始 YouTube 标题/简介处理成「可安全交给模型」的输入，并对模型输出做
 * 质量把关（空、导流、与原文过于相似、非自然简介）。</p>
 */
public final class TextSanitizer {

    /** 导流/社媒/互动引导信号词（小写匹配）。 */
    private static final List<String> PROMO_SIGNALS = List.of(
            "follow me", "subscribe", "patreon", "instagram", "twitter", "facebook",
            "tiktok", "discord", "telegram", "business inquiry", "sponsor",
            "关注我", "订阅", "点赞", "投币", "收藏", "转发", "加微信", "公众号",
            "商务合作", "合作邮箱", "粉丝群", "交流群", "打赏", "捐赠",
            "http://", "https://", "www.", ".com/", ".cn/"
    );

    /** 列表/备注式行首特征（简介不应写成列表）。 */
    private static final Pattern LIST_PREFIX = Pattern.compile(
            "^\\s*([-*•·▪◦]|\\d+[.)、]|[（(]?\\d+[）)]|[a-zA-Z][.)])\\s+");

    private static final Pattern MULTI_WHITESPACE = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    private TextSanitizer() {
    }

    /** 归一化空白（对齐 _normalize_whitespace）。 */
    public static String normalizeWhitespace(String text) {
        if (text == null) return "";
        String s = MULTI_WHITESPACE.matcher(text).replaceAll(" ");
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    /** 输入清洗（对齐 _pre_clean）：去导流、压空行、标题单行化。 */
    public static String preClean(String text, String contentType, int maxBlocks) {
        if (text == null || text.isBlank()) return "";
        boolean isTitle = "title".equalsIgnoreCase(contentType);

        String working = text.replace("\r\n", "\n").replace('\r', '\n');
        if (isTitle) {
            working = working.replace('\n', ' ');
        }

        List<String> keptBlocks = new ArrayList<>();
        for (String block : working.split("\n")) {
            String line = normalizeWhitespace(block);
            if (line.isEmpty()) {
                keptBlocks.add("");
                continue;
            }
            if (containsPromoSignal(line)) {
                continue;   // 丢弃含导流信号的行
            }
            if (!isTitle && LIST_PREFIX.matcher(line).find()) {
                // 简介里丢掉列表前缀但保留内容
                line = LIST_PREFIX.matcher(line).replaceFirst("").trim();
                if (line.isEmpty()) continue;
            }
            keptBlocks.add(line);
        }

        // 合并连续空行，最多保留 maxBlocks 段
        StringBuilder sb = new StringBuilder();
        int blockCount = 0;
        boolean lastBlank = true;
        for (String line : keptBlocks) {
            if (line.isEmpty()) {
                if (!lastBlank && blockCount < maxBlocks) {
                    sb.append('\n');
                    lastBlank = true;
                }
                continue;
            }
            if (blockCount >= maxBlocks && !lastBlank) {
                break;
            }
            if (sb.length() > 0 && !lastBlank) sb.append(isTitle ? ' ' : '\n');
            sb.append(line);
            lastBlank = false;
            blockCount++;
        }

        String result = normalizeWhitespace(sb.toString());
        if (isTitle) {
            result = result.replace('\n', ' ');
        }
        return result.trim();
    }

    /** 是否包含导流信号。 */
    public static boolean containsPromoSignal(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String signal : PROMO_SIGNALS) {
            if (lower.contains(signal)) return true;
        }
        return false;
    }

    /** 是否有实质内容（对齐 _has_meaningful_content）。 */
    public static boolean hasMeaningfulContent(String text, String contentType) {
        if (text == null) return false;
        String s = text.trim();
        if (s.length() < 2) return false;
        // 纯符号/纯数字视为无意义
        long meaningful = s.chars()
                .filter(c -> Character.isLetterOrDigit(c) || Character.UnicodeScript
                        .of(c) != Character.UnicodeScript.COMMON)
                .count();
        if (meaningful < 2) return false;
        if ("title".equalsIgnoreCase(contentType) && s.length() > 300) return false;
        return true;
    }

    /** 输出校验（对齐 _validate_output）。 */
    public record ValidationResult(boolean passed, List<String> reasons) {
        public String summary() {
            return passed ? "ok" : String.join(",", reasons);
        }
    }

    public static ValidationResult validateOutput(String sourceClean, String output,
                                                  String contentType, int maxBlocks) {
        List<String> reasons = new ArrayList<>();
        String out = output == null ? "" : output.trim();
        String src = sourceClean == null ? "" : sourceClean.trim();
        boolean isTitle = "title".equalsIgnoreCase(contentType);

        if (out.isEmpty()) {
            reasons.add("empty_output");
        }
        if (containsPromoSignal(out)) {
            reasons.add("contains_promo_signal");
        }

        String srcNorm = normalizeForSimilarity(src);
        String outNorm = normalizeForSimilarity(out);
        if (!srcNorm.isEmpty() && !outNorm.isEmpty()) {
            if (srcNorm.length() >= 12 && outNorm.length() >= 8) {
                double ratio = similarity(srcNorm, outNorm);
                if (ratio >= 0.90) {
                    reasons.add(String.format("too_similar:%.2f", ratio));
                }
            } else if (srcNorm.equals(outNorm) && srcNorm.length() >= 6) {
                reasons.add("identical_to_source");
            }
        }

        if (!isTitle && !out.isEmpty() && !isNaturalDescription(out, maxBlocks)) {
            reasons.add("description_not_natural");
        }

        return new ValidationResult(reasons.isEmpty(), reasons);
    }

    /** 简介是否自然（对齐 _is_natural_description）：不能是列表/备注/说明。 */
    public static boolean isNaturalDescription(String text, int maxBlocks) {
        if (text == null || text.isBlank()) return false;
        String[] lines = text.split("\n");
        int nonEmpty = 0;
        int listLike = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            nonEmpty++;
            if (LIST_PREFIX.matcher(line).find()) listLike++;
            if (line.startsWith("备注") || line.startsWith("说明")
                    || line.startsWith("注：") || line.startsWith("Note:")) {
                listLike++;
            }
        }
        if (nonEmpty == 0) return false;
        // 超过一半的行像列表，判定非自然
        return listLike * 2 <= nonEmpty;
    }

    /** 应用长度上限（对齐 _apply_output_limits）。 */
    public static String applyLimits(String text, String contentType,
                                     int titleLimit, int descriptionLimit) {
        if (text == null) return "";
        boolean isTitle = "title".equalsIgnoreCase(contentType);
        if (isTitle) {
            return text.length() > titleLimit ? text.substring(0, titleLimit) : text;
        }
        if (text.length() > descriptionLimit) {
            return descriptionLimit > 3
                    ? text.substring(0, descriptionLimit - 3) + "..."
                    : text.substring(0, descriptionLimit);
        }
        return text;
    }

    /** 相似度归一化（去标点/空白/小写）。 */
    private static String normalizeForSimilarity(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replaceAll("[\\p{Punct}\\s]", "")
                .trim();
    }

    /** 归一化 Levenshtein 相似度（等价于 Python difflib ratio）。 */
    private static double similarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshtein(a, b);
        return 1.0 - (double) distance / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
