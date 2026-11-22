package com.y2a.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.common.exception.BizException;
import com.y2a.server.domain.task.Task;
import com.y2a.server.event.TaskEventPublisher;
import com.y2a.server.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务应用服务。
 *
 * <p>状态迁移统一走 {@link #changeStatus(String, TaskStatus)} 由状态机校验；
 * {@link #changeStatusTo(String, TaskStatus)} 供「强制上传」这类需要跳过常规迁移
 * 的运维动作使用（原版 force_upload 会直接把任务推进到上传阶段）。</p>
 */
@Service
public class TaskService {

    private final TaskRepository repository;
    private final TaskEventPublisher eventPublisher;

    public TaskService(TaskRepository repository, TaskEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public IPage<Task> list(int page, int size, String status, String target, String keyword) {
        return repository.search(new Page<>(page, size), blankToNull(status),
                blankToNull(target), blankToNull(keyword));
    }

    public List<Task> listByStatus(String status) {
        return repository.selectList(new QueryWrapper<Task>()
                .eq("status", status).orderByDesc("updated_at"));
    }

    public Task get(String id) {
        Task t = repository.selectById(id);
        if (t == null) {
            throw BizException.notFound("任务不存在: " + id);
        }
        return t;
    }

    @Transactional
    public Task create(String youtubeUrl, String uploadTarget) {
        UploadTarget target = UploadTarget.fromValue(uploadTarget);
        Task task = Task.builder()
                .id(UUID.randomUUID().toString())
                .youtubeUrl(youtubeUrl)
                .uploadTarget(target.value())
                .status(TaskStatus.PENDING.value())
                .build();
        repository.insert(task);
        eventPublisher.published(task.getId(), "task_added", Map.of("target", target.value()));
        return task;
    }

    @Transactional
    public Task updateMetadata(String id, String title, String description, List<String> tags) {
        Task task = get(id);
        if (title != null && !title.isBlank()) task.setVideoTitleTranslated(title);
        if (description != null) task.setDescriptionTranslated(description);
        if (tags != null) task.setTagsGenerated(tags);
        repository.updateById(task);
        eventPublisher.published(id, "task_updated", Map.of());
        return task;
    }

    /** 常规状态迁移：状态机校验。 */
    @Transactional
    public Task changeStatus(String id, TaskStatus next) {
        Task task = get(id);
        TaskStatus current = task.statusEnum();
        if (!current.canTransitionTo(next)) {
            throw BizException.invalidState("状态不允许迁移: " + current.value() + " -> " + next.value());
        }
        task.setStatus(next.value());
        repository.updateById(task);
        eventPublisher.published(id, "status_changed",
                Map.of("from", current.value(), "to", next.value()));
        return task;
    }

    /** 运维动作强制置位（如强制上传），跳过常规状态机。 */
    @Transactional
    public Task changeStatusTo(String id, TaskStatus next) {
        Task task = get(id);
        task.setStatus(next.value());
        repository.updateById(task);
        eventPublisher.published(id, "status_changed", Map.of("to", next.value(), "forced", true));
        return task;
    }

    @Transactional
    public void markAbandoned(String id) {
        Task task = get(id);
        task.setStatus(TaskStatus.FAILED.value());
        task.setErrorMessage("用户主动放弃任务");
        repository.updateById(task);
        eventPublisher.published(id, "task_failed", Map.of("reason", "abandoned"));
    }

    /** 重试翻译：清空翻译结果并回到 pending。 */
    @Transactional
    public void resetForRetry(String id) {
        Task task = get(id);
        task.setVideoTitleTranslated(null);
        task.setDescriptionTranslated(null);
        task.setTagsGenerated(null);
        task.setStatus(TaskStatus.PENDING.value());
        task.setErrorMessage(null);
        task.setErrorCategory(null);
        repository.updateById(task);
        eventPublisher.published(id, "task_updated", Map.of("retry", "translation"));
    }

    @Transactional
    public int retryAllFailed() {
        List<Task> failed = listByStatus(TaskStatus.FAILED.value());
        int count = 0;
        for (Task t : failed) {
            t.setStatus(TaskStatus.PENDING.value());
            t.setErrorMessage(null);
            repository.updateById(t);
            count++;
        }
        if (count > 0) eventPublisher.published(null, "tasks_cleared", Map.of("retried", count));
        return count;
    }

    /** 把处理中但长时间未更新的任务重置为 pending。 */
    @Transactional
    public int resetStuck() {
        List<String> processing = List.of("downloading", "uploading", "translating", "tagging",
                "partitioning", "moderating", "asr_transcribing", "translating_subtitle", "encoding_video");
        List<Task> stuck = repository.selectList(new QueryWrapper<Task>()
                .in("status", processing)
                .apply("updated_at < now() - interval '30 minutes'"));
        int count = 0;
        for (Task t : stuck) {
            t.setStatus(TaskStatus.PENDING.value());
            t.setUploadProgress(null);
            repository.updateById(t);
            count++;
        }
        return count;
    }

    @Transactional
    public void delete(String id) {
        get(id);
        repository.deleteById(id);
        eventPublisher.published(id, "task_deleted", Map.of());
    }

    @Transactional
    public int deleteAll() {
        int n = repository.delete(Wrappers.emptyWrapper());
        eventPublisher.published(null, "tasks_cleared", Map.of("deleted", n));
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
