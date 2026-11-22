package com.y2a.server.common.constant;

import java.util.List;

/** 流水线阶段 —— 与 Python 版 PIPELINE_STAGE_* + PIPELINE_STAGE_ORDER 对齐。 */
public enum PipelineStage {

    FETCH_INFO("fetch_info"),
    TRANSLATE_CONTENT("translate_content"),
    GENERATE_TAGS("generate_tags"),
    RECOMMEND_PARTITION("recommend_partition"),
    MODERATE_CONTENT("moderate_content"),
    DOWNLOAD_VIDEO("download_video"),
    TRANSLATE_SUBTITLE("translate_subtitle"),
    UPLOAD("upload");

    public static final List<PipelineStage> ORDER = List.of(
            FETCH_INFO, TRANSLATE_CONTENT, GENERATE_TAGS, RECOMMEND_PARTITION,
            MODERATE_CONTENT, DOWNLOAD_VIDEO, TRANSLATE_SUBTITLE, UPLOAD
    );

    private final String value;

    PipelineStage(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
