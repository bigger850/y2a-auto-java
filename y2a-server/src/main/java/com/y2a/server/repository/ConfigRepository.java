package com.y2a.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.y2a.server.domain.config.AppConfigEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 配置数据访问层。 */
@Mapper
public interface ConfigRepository extends BaseMapper<AppConfigEntry> {

    @Select("SELECT * FROM app_config ORDER BY category, config_key")
    List<AppConfigEntry> findAll();

    @Select("SELECT * FROM app_config WHERE category = #{category} ORDER BY config_key")
    List<AppConfigEntry> findByCategory(@Param("category") String category);

    @Update("""
            UPDATE app_config
            SET config_value = #{value}::jsonb, updated_at = now()
            WHERE config_key = #{key}
            """)
    int updateValue(@Param("key") String key, @Param("value") String value);
}
