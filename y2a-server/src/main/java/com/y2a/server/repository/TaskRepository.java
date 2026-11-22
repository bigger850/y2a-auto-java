package com.y2a.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.y2a.server.domain.task.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 任务数据访问层。基础 CRUD 由 MyBatis-Plus 提供，复杂查询写在这里。 */
@Mapper
public interface TaskRepository extends BaseMapper<Task> {

    @Select("""
            <script>
            SELECT * FROM tasks
            <where>
              <if test="status != null and status != ''"> AND status = #{status} </if>
              <if test="target != null and target != ''"> AND upload_target = #{target} </if>
              <if test="keyword != null and keyword != ''">
                AND (video_title_original ILIKE '%' || #{keyword} || '%'
                     OR video_title_translated ILIKE '%' || #{keyword} || '%'
                     OR youtube_url ILIKE '%' || #{keyword} || '%')
              </if>
            </where>
            ORDER BY updated_at DESC
            </script>
            """)
    IPage<Task> search(Page<?> page,
                       @Param("status") String status,
                       @Param("target") String target,
                       @Param("keyword") String keyword);
}
