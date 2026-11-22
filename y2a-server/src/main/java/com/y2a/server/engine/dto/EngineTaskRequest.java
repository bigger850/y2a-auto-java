package com.y2a.server.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Java → Python 引擎的通用任务请求。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineTaskRequest {
    private String taskId;
    private String stage;
    /** 阶段所需参数（文件路径、配置快照等） */
    private Map<String, Object> payload;
    /** 引擎完成后回调 Java 的地址（含 taskId/stage） */
    private String callbackUrl;
}
