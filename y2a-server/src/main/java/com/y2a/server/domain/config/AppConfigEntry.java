package com.y2a.server.domain.config;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 配置项实体（app_config 表）。值统一以 JSONB 存储，保留原始类型。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "app_config", autoResultMap = true)
public class AppConfigEntry {
    @TableId
    private String configKey;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object configValue;

    private String category;
    private Instant updatedAt;
}
