package com.y2a.server.domain.task;

import com.baomidou.mybatisplus.annotation.*;
import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.common.constant.UploadTarget;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 任务实体 —— 对应 PostgreSQL tasks 表。
 * 使用 MyBatis-Plus 注解，JSONB 字段用 JacksonTypeHandler 映射。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "tasks", autoResultMap = true)
public class Task {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String youtubeUrl;

    private String uploadTarget;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    private String videoTitleOriginal;
    private String videoTitleTranslated;
    private String descriptionOriginal;
    private String descriptionTranslated;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> tagsGenerated;

    private String recommendedPartitionId;
    private String selectedPartitionId;
    private String recommendedPartitionIdAcfun;
    private String selectedPartitionIdAcfun;
    private String recommendedPartitionIdBilibili;
    private String selectedPartitionIdBilibili;

    private String coverPathLocal;
    private String videoPathLocal;
    private String subtitlePathOriginal;
    private String subtitlePathTranslated;
    private String subtitleLanguageDetected;

    private Boolean subtitleQcFailed;
    private String subtitleQcReason;
    private Double subtitleQcScore;
    private Instant subtitleQcCheckedAt;

    private String metadataJsonPathLocal;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> moderationResult;

    private String errorMessage;
    private String errorCategory;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> pipelineCheckpoint;

    private String uploadProgress;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> acfunUploadResponse;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> bilibiliUploadResponse;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> douyinUploadResponse;

    private String asrWarningMessage;
    private String subtitleWarningMessage;
    private String subtitleQualityState;

    // ---------- 领域行为（充血模型，避免贫血实体） ----------

    public TaskStatus statusEnum() {
        return TaskStatus.fromValue(status);
    }

    public UploadTarget targetEnum() {
        return UploadTarget.fromValue(uploadTarget);
    }

    public String displayTitle() {
        return videoTitleTranslated != null && !videoTitleTranslated.isBlank()
                ? videoTitleTranslated : videoTitleOriginal;
    }

    public boolean hasUploadResponseFor(String platform) {
        return switch (platform) {
            case "acfun" -> acfunUploadResponse != null && !acfunUploadResponse.isEmpty();
            case "bilibili" -> bilibiliUploadResponse != null && !bilibiliUploadResponse.isEmpty();
            case "douyin" -> douyinUploadResponse != null && !douyinUploadResponse.isEmpty();
            default -> false;
        };
    }
}
