package com.y2a.server.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Python 引擎 → Java 的阶段完成回调（回调模式核心 DTO）。
 * 长任务（下载/ASR/编码/上传）由引擎异步执行，完成后 POST 本对象到 Java。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineCallback {
    private String taskId;
    private String stage;
    private boolean success;
    private double progress;
    /** 成功时的阶段产出（文件路径、upload response 等） */
    private Map<String, Object> result;
    /** 失败时的错误信息 */
    private String error;
}
