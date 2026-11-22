package com.y2a.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.y2a.server.domain.monitor.MonitorConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/** 监控配置仓储。 */
@Mapper
public interface MonitorConfigRepository extends BaseMapper<MonitorConfig> {

    @Select("SELECT * FROM monitor_configs ORDER BY id DESC")
    List<MonitorConfig> findAllOrdered();

    @Update("UPDATE monitor_configs SET last_run_time = #{time}, updated_time = now() WHERE id = #{id}")
    int touchLastRun(@Param("id") Long id, @Param("time") Instant time);
}
