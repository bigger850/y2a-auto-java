package com.y2a.server.domain.monitor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 监控历史记录（monitor_history）。configName 为 join 出来的展示字段。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("monitor_history")
public class MonitorHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long configId;
    private String videoId;
    private String videoType;
    private String videoTitle;
    private String channelTitle;
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private String duration;
    private String publishedAt;
    private Boolean addedToTasks;
    private Double taskClaimedAt;
    private Instant runTime;

    /** 非表字段：join monitor_configs.name。 */
    @TableField(exist = false)
    private String configName;
}
