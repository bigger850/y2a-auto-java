package com.y2a.server;

import com.y2a.server.ai.TextSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 文本清洗与输出校验的单元测试（不依赖外部 API）。 */
class TextSanitizerTest {

    @Test
    void preCleanRemovesPromoLines() {
        String raw = "Check out my Instagram: @creator\n"
                + "This is a great tutorial about Java.\n"
                + "Subscribe to my channel!\n"
                + "Thanks for watching.";
        String cleaned = TextSanitizer.preClean(raw, "description", 2);

        assertThat(cleaned).contains("great tutorial");
        assertThat(cleaned).doesNotContain("Instagram");
        assertThat(cleaned).doesNotContain("Subscribe");
        assertThat(TextSanitizer.containsPromoSignal(cleaned)).isFalse();
    }

    @Test
    void preCleanMakesTitleSingleLine() {
        String raw = "Java  Tutorial\nfor Beginners";
        String cleaned = TextSanitizer.preClean(raw, "title", 1);

        assertThat(cleaned).doesNotContain("\n");
        assertThat(cleaned).contains("Java");
        assertThat(cleaned).contains("Beginners");
    }

    @Test
    void preCleanCollapsesBlankLines() {
        String raw = "段落一\n\n\n\n\n段落二";
        String cleaned = TextSanitizer.preClean(raw, "description", 2);

        assertThat(cleaned.split("\n").length).isLessThanOrEqualTo(2);
    }

    @Test
    void validateOutputRejectsEmpty() {
        var result = TextSanitizer.validateOutput("source", "", "title", 2);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("empty_output");
    }

    @Test
    void validateOutputRejectsIdenticalToSource() {
        String src = "Learn Java Programming in 2024";
        var result = TextSanitizer.validateOutput(src, src, "title", 2);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().stream().anyMatch(r ->
                r.startsWith("too_similar") || r.equals("identical_to_source"))).isTrue();
    }

    @Test
    void validateOutputRejectsPromoSignal() {
        var result = TextSanitizer.validateOutput("source text here",
                "关注我学Java", "title", 2);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).contains("contains_promo_signal");
    }

    @Test
    void validateOutputAcceptsProperTranslation() {
        String src = "Learn Java Programming in 2024";
        var result = TextSanitizer.validateOutput(src,
                "2024 年 Java 编程入门教程", "title", 2);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void listStyleDescriptionIsNotNatural() {
        assertThat(TextSanitizer.isNaturalDescription(
                "- 第一点\n- 第二点\n- 第三点", 2)).isFalse();
    }

    @Test
    void paragraphDescriptionIsNatural() {
        assertThat(TextSanitizer.isNaturalDescription(
                "这是一段自然的简介文字。\n\n第二段继续介绍视频主题。", 2)).isTrue();
    }

    @Test
    void applyLimitsTruncatesTitle() {
        // 构造超过 30 字的标题，验证截断到上限
        String longTitle = "这是一个非常长的中文标题用于测试标题截断功能是否能够正常工作并正确生效";
        assertThat(longTitle.length()).isGreaterThan(30);
        String limited = TextSanitizer.applyLimits(longTitle, "title", 30, 1000);
        assertThat(limited.length()).isEqualTo(30);
    }

    @Test
    void applyLimitsAddsEllipsisToDescription() {
        String longDesc = "a".repeat(1200);
        String limited = TextSanitizer.applyLimits(longDesc, "description", 50, 1000);
        assertThat(limited.length()).isEqualTo(1000);
        assertThat(limited).endsWith("...");
    }

    @Test
    void hasMeaningfulContentRejectsSymbolsOnly() {
        assertThat(TextSanitizer.hasMeaningfulContent("---", "title")).isFalse();
        assertThat(TextSanitizer.hasMeaningfulContent("Java 教程", "title")).isTrue();
    }
}
