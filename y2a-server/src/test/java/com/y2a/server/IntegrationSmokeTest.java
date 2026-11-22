package com.y2a.server;

import com.y2a.server.common.constant.TaskStatus;
import com.y2a.server.common.constant.UploadTarget;
import com.y2a.server.domain.task.Task;
import com.y2a.server.repository.ConfigRepository;
import com.y2a.server.repository.TaskRepository;
import com.y2a.server.service.TaskService;
import com.y2a.server.event.TaskEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 阶段 0/1 冒烟测试：真实 PG + Flyway + CRUD + 状态机。 */
@SpringBootTest
@Testcontainers
class IntegrationSmokeTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("y2a").withUsername("y2a").withPassword("y2a");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("engine.base-url", () -> "http://localhost:1"); // 引擎不可达，不影响本测试
    }

    @Autowired TaskRepository taskRepository;
    @Autowired ConfigRepository configRepository;
    @Autowired TaskService taskService;
    @Autowired TaskEventPublisher eventPublisher;

    @Test
    void flywaySeeds208Configs() {
        List<?> all = configRepository.findAll();
        assertThat(all).hasSizeGreaterThanOrEqualTo(208);
        assertThat(configRepository.selectById("UPLOAD_TARGET_DEFAULT")).isNotNull();
        assertThat(configRepository.selectById("DOUYIN_COOKIES_PATH")).isNotNull();
    }

    @Test
    void taskCrudAndStateMachine() {
        Task created = taskService.create("https://www.youtube.com/watch?v=test", "douyin");
        assertThat(created.getId()).isNotBlank();
        assertThat(created.getStatus()).isEqualTo(TaskStatus.PENDING.value());
        assertThat(created.getUploadTarget()).isEqualTo(UploadTarget.DOUYIN.value());

        Task started = taskService.changeStatus(created.getId(), TaskStatus.DOWNLOADING);
        assertThat(started.getStatus()).isEqualTo("downloading");

        // 非法迁移：downloading -> completed 应被状态机拒绝
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> taskService.changeStatus(created.getId(), TaskStatus.COMPLETED)))
                .isInstanceOf(com.y2a.server.common.exception.BizException.class);

        taskService.delete(created.getId());
        assertThat(taskRepository.selectById(created.getId())).isNull();
    }
}
