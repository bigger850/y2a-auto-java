package com.y2a.server.repository;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 通知 outbox 数据访问。 */
@Mapper
public interface OutboxRepository {

    @Insert("""
            INSERT INTO notification_outbox (event_type, payload, status, attempts, next_retry_at, created_at)
            VALUES (#{eventType}, #{payload}::jsonb, 'pending', 0, now(), now())
            """)
    int enqueue(@Param("eventType") String eventType, @Param("payload") String payload);

    @Select("""
            SELECT id, event_type, payload::text AS payload, attempts
            FROM notification_outbox
            WHERE status = 'pending' AND next_retry_at <= now()
            ORDER BY created_at LIMIT 50
            """)
    List<Map<String, Object>> dueMessages();

    @Update("UPDATE notification_outbox SET status = 'sent', sent_at = now() WHERE id = #{id}")
    int markSent(@Param("id") long id);

    @Update("""
            UPDATE notification_outbox
            SET attempts = #{attempts}, next_retry_at = #{nextRetry}, last_error = #{error}
            WHERE id = #{id}
            """)
    int markFailed(@Param("id") long id, @Param("attempts") int attempts,
                   @Param("nextRetry") Instant nextRetry, @Param("error") String error);
}
