package com.y2a.server.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 首页统计查询（对应 Python index 路由里的手写 SQL）。 */
@Mapper
public interface DashboardRepository {

    @Select("SELECT COUNT(*) FROM tasks")
    long totalTasks();

    @Select("SELECT COUNT(*) FROM tasks WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    @Select("""
            SELECT COUNT(*) FROM tasks
            WHERE status IN ('fetching_info','info_fetched','translating','tagging','partitioning',
                             'moderating','downloading','downloaded','asr_transcribing',
                             'translating_subtitle','encoding_video','uploading')
            """)
    long inProgress();

    @Select("""
            SELECT COUNT(*) FROM tasks
            WHERE status = #{status} AND updated_at >= #{start} AND updated_at < #{end}
            """)
    long countByStatusToday(@Param("status") String status,
                            @Param("start") java.time.Instant start,
                            @Param("end") java.time.Instant end);

    @Select("SELECT COUNT(*) FROM tasks WHERE created_at >= #{start} AND created_at < #{end}")
    long createdToday(@Param("start") java.time.Instant start, @Param("end") java.time.Instant end);
}
