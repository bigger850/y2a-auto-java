package com.y2a.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.y2a.server.domain.monitor.MonitorHistory;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/** 监控历史仓储。 */
@Mapper
public interface MonitorHistoryRepository extends BaseMapper<MonitorHistory> {

    @Select("""
            SELECT h.*, c.name AS config_name
            FROM monitor_history h
            LEFT JOIN monitor_configs c ON c.id = h.config_id
            ORDER BY h.run_time DESC
            LIMIT #{limit}
            """)
    List<MonitorHistory> findRecent(@Param("limit") int limit);

    @Select("""
            SELECT h.*, c.name AS config_name
            FROM monitor_history h
            LEFT JOIN monitor_configs c ON c.id = h.config_id
            WHERE h.config_id = #{configId}
            ORDER BY h.run_time DESC
            LIMIT #{limit}
            """)
    List<MonitorHistory> findByConfig(@Param("configId") Long configId, @Param("limit") int limit);

    @Select("""
            SELECT
              COUNT(*)                                   AS total_records,
              COALESCE(SUM(CASE WHEN added_to_tasks THEN 1 ELSE 0 END), 0) AS added_to_tasks,
              COALESCE(AVG(view_count), 0)               AS avg_views,
              COALESCE(AVG(like_count), 0)               AS avg_likes
            FROM monitor_history WHERE config_id = #{configId}
            """)
    Map<String, Object> statsByConfig(@Param("configId") Long configId);

    @Delete("DELETE FROM monitor_history WHERE config_id = #{configId}")
    int deleteByConfig(@Param("configId") Long configId);

    @Delete("DELETE FROM monitor_history")
    int deleteAll();
}
