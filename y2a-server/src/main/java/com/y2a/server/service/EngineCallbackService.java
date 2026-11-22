package com.y2a.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.y2a.server.common.constant.PipelineStage;
import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.domain.task.Task;
import com.y2a.server.engine.dto.EngineCallback;
import com.y2a.server.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 引擎回调处理器：接收阶段执行结果，更新任务状态与产出。
 *
 * <p>用「策略分派」把不同 stage 的产出映射到不同字段，新增阶段只需加一个 case，
 * 不改动回调入口（开闭原则）。阶段 2-3 会逐步填充各 stage 的真实处理。</p>
 */
@Service
public class EngineCallbackService {

    private static final Logger log = LoggerFactory.getLogger(EngineCallbackService.class);

    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public EngineCallbackService(TaskRepository taskRepository, TaskService taskService,
                                 ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handle(EngineCallback callback) {
        Task task = taskService.get(callback.getTaskId());
        log.info("引擎回调: taskId={} stage={} success={} progress={}",
                callback.getTaskId(), callback.getStage(), callback.isSuccess(), callback.getProgress());

        if (!callback.isSuccess()) {
            task.setErrorMessage(callback.getError());
            task.setStatus(TaskStatus.FAILED.value());
            taskRepository.updateById(task);
            return;
        }

        // 把阶段产出落到任务字段（策略分派，后续阶段扩展这里）
        applyStageResult(task, callback.getStage(), callback.getResult());

        if (callback.getProgress() >= 100) {
            TaskStatus next = nextStatusOf(callback.getStage());
            if (next != TaskStatus.UPLOADING || task.statusEnum().canTransitionTo(next)) {
                task.setStatus(next.value());
            }
        }
        task.setUploadProgress(String.format("%.1f%%", callback.getProgress()));
        taskRepository.updateById(task);
    }

    private void applyStageResult(Task task, String stage, Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        switch (stage) {
            case "fetch_info" -> {
                if (result.get("title") != null) task.setVideoTitleOriginal(String.valueOf(result.get("title")));
                if (result.get("description") != null) task.setDescriptionOriginal(String.valueOf(result.get("description")));
                if (result.get("metadataPath") != null) task.setMetadataJsonPathLocal(String.valueOf(result.get("metadataPath")));
            }
            case "download_video" -> {
                if (result.get("videoPath") != null) task.setVideoPathLocal(String.valueOf(result.get("videoPath")));
                if (result.get("coverPath") != null) task.setCoverPathLocal(String.valueOf(result.get("coverPath")));
            }
            case "translate_subtitle" -> {
                if (result.get("subtitlePath") != null) task.setSubtitlePathTranslated(String.valueOf(result.get("subtitlePath")));
            }
            case "upload" -> {
                String platform = String.valueOf(result.getOrDefault("platform", ""));
                Map<String, Object> response = objectMapper.convertValue(result, Map.class);
                switch (platform) {
                    case "acfun" -> task.setAcfunUploadResponse(response);
                    case "bilibili" -> task.setBilibiliUploadResponse(response);
                    case "douyin" -> task.setDouyinUploadResponse(response);
                    default -> log.warn("未知上传平台: {}", platform);
                }
            }
            default -> log.debug("阶段 {} 无特殊产出映射", stage);
        }
    }

    /** 阶段成功后的下一个状态。阶段2会替换为状态机驱动。 */
    private TaskStatus nextStatusOf(String stage) {
        return switch (stage) {
            case "download_video" -> TaskStatus.DOWNLOADED;
            case "translate_subtitle" -> TaskStatus.ENCODING_VIDEO;
            case "upload" -> TaskStatus.COMPLETED;
            default -> TaskStatus.READY_FOR_UPLOAD;
        };
    }
}
