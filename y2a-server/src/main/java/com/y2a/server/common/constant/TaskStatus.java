package com.y2a.server.common.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态枚举 —— 与 Python 版 TASK_STATES 对齐（15 个状态）。
 *
 * <p>每个状态声明其允许的后续状态，{@link #canTransitionTo(TaskStatus)} 提供
 * 状态机校验能力。这是用「枚举 + 集中式状态机」替代 Python 版散落各处的 if 判断，
 * 保证状态迁移集中、可测试、可扩展。</p>
 *
 * <p>注意：Java 枚举构造器里不能前向引用其它枚举常量，因此迁移表在静态块中初始化。</p>
 */
public enum TaskStatus {

    PENDING("pending"),
    DOWNLOADING("downloading"),
    DOWNLOADED("downloaded"),
    ASR_TRANSCRIBING("asr_transcribing"),
    TRANSLATING_SUBTITLE("translating_subtitle"),
    ENCODING_VIDEO("encoding_video"),
    TRANSLATING("translating"),
    TAGGING("tagging"),
    PARTITIONING("partitioning"),
    MODERATING("moderating"),
    AWAITING_REVIEW("awaiting_manual_review"),
    READY_FOR_UPLOAD("ready_for_upload"),
    UPLOADING("uploading"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        TRANSITIONS.put(PENDING, Set.of(DOWNLOADING, TRANSLATING, TAGGING, PARTITIONING,
                MODERATING, AWAITING_REVIEW, READY_FOR_UPLOAD, FAILED));
        TRANSITIONS.put(DOWNLOADING, Set.of(DOWNLOADED, FAILED, AWAITING_REVIEW));
        TRANSITIONS.put(DOWNLOADED, Set.of(ASR_TRANSCRIBING, TRANSLATING_SUBTITLE,
                ENCODING_VIDEO, READY_FOR_UPLOAD, FAILED));
        TRANSITIONS.put(ASR_TRANSCRIBING, Set.of(TRANSLATING_SUBTITLE, DOWNLOADED, FAILED));
        TRANSITIONS.put(TRANSLATING_SUBTITLE, Set.of(ENCODING_VIDEO, DOWNLOADED, FAILED));
        TRANSITIONS.put(ENCODING_VIDEO, Set.of(READY_FOR_UPLOAD, FAILED));
        TRANSITIONS.put(TRANSLATING, Set.of(TAGGING, PARTITIONING, MODERATING,
                AWAITING_REVIEW, READY_FOR_UPLOAD, FAILED));
        TRANSITIONS.put(TAGGING, Set.of(PARTITIONING, MODERATING, AWAITING_REVIEW,
                READY_FOR_UPLOAD, FAILED));
        TRANSITIONS.put(PARTITIONING, Set.of(MODERATING, AWAITING_REVIEW, READY_FOR_UPLOAD, FAILED));
        TRANSITIONS.put(MODERATING, Set.of(AWAITING_REVIEW, READY_FOR_UPLOAD, FAILED));
        TRANSITIONS.put(AWAITING_REVIEW, Set.of(READY_FOR_UPLOAD, UPLOADING, FAILED));
        TRANSITIONS.put(READY_FOR_UPLOAD, Set.of(UPLOADING, FAILED));
        TRANSITIONS.put(UPLOADING, Set.of(COMPLETED, FAILED, AWAITING_REVIEW));
        TRANSITIONS.put(COMPLETED, Set.of());
        TRANSITIONS.put(FAILED, Set.of(PENDING));
    }

    TaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean canTransitionTo(TaskStatus next) {
        return next != null && TRANSITIONS.get(this).contains(next);
    }

    public Set<TaskStatus> allowedNext() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }

    public static TaskStatus fromValue(String value) {
        if (value == null) {
            return PENDING;
        }
        return Arrays.stream(values())
                .filter(s -> s.value.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(PENDING);
    }

    /** 是否属于「处理中」状态（用于卡住任务扫描与崩溃恢复）。 */
    public boolean isProcessing() {
        return this != PENDING && this != COMPLETED && this != FAILED && this != AWAITING_REVIEW;
    }
}
