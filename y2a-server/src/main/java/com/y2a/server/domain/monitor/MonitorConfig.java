package com.y2a.server.domain.monitor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** YouTube 监控配置（monitor_configs）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("monitor_configs")
public class MonitorConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Boolean enabled;

    private String regionCode;
    private String categoryId;
    private Integer timePeriod;
    private Integer maxResults;
    private Integer minViewCount;
    private Integer minLikeCount;
    private Integer minCommentCount;

    private String keywords;
    private String excludeKeywords;
    private String titleKeywords;
    private String titleMatchMode;

    private String channelIds;
    private String channelKeywords;
    private String excludeChannelIds;

    private Integer minDuration;
    private Integer maxDuration;

    private String scheduleType;
    private Integer scheduleInterval;
    private String orderBy;
    private String startDate;
    private String endDate;
    private Integer latestDays;
    private Integer latestMaxResults;
    private Integer rateLimitRequests;
    private Integer rateLimitWindow;

    private Boolean autoAddToTasks;
    private String monitorType;
    private String channelMode;
    private String videoTypes;

    private String historicalProgressDate;
    private Integer historicalOffset;

    private Instant lastRunTime;
    private Instant createdTime;
    private Instant updatedTime;
}
