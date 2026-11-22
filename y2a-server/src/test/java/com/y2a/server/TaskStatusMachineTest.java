package com.y2a.server;

import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.common.constant.UploadTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 纯单元测试：状态机与平台枚举（不需要 DB / Spring 上下文）。 */
class TaskStatusMachineTest {

    @Test
    void allowsLegalTransitions() {
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.DOWNLOADING)).isTrue();
        assertThat(TaskStatus.DOWNLOADING.canTransitionTo(TaskStatus.DOWNLOADED)).isTrue();
        assertThat(TaskStatus.UPLOADING.canTransitionTo(TaskStatus.COMPLETED)).isTrue();
        assertThat(TaskStatus.FAILED.canTransitionTo(TaskStatus.PENDING)).isTrue();
    }

    @Test
    void rejectsIllegalTransitions() {
        assertThat(TaskStatus.DOWNLOADING.canTransitionTo(TaskStatus.COMPLETED)).isFalse();
        assertThat(TaskStatus.COMPLETED.canTransitionTo(TaskStatus.PENDING)).isFalse();
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.UPLOADING)).isFalse();
    }

    @Test
    void parsesValuesAndDefaults() {
        assertThat(TaskStatus.fromValue("awaiting_manual_review")).isEqualTo(TaskStatus.AWAITING_REVIEW);
        assertThat(TaskStatus.fromValue("unknown")).isEqualTo(TaskStatus.PENDING);
        assertThat(TaskStatus.fromValue(null)).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void uploadTargetExpansion() {
        assertThat(UploadTarget.fromValue("douyin")).isEqualTo(UploadTarget.DOUYIN);
        assertThat(UploadTarget.BOTH.platforms()).containsExactly(UploadTarget.ACFUN, UploadTarget.BILIBILI);
        assertThat(UploadTarget.DOUYIN.platforms()).containsExactly(UploadTarget.DOUYIN);
    }
}
