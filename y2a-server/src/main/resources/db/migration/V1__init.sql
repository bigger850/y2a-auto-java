-- ============================================================
-- Y2A-Auto PostgreSQL schema (migrated from SQLite)
-- 表名/字段尽量与 Python 版保持一致，便于数据迁移对照
-- ============================================================

-- 通用函数：updated_at 自动更新
CREATE OR REPLACE FUNCTION y2a_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ------------------------------------------------------------
-- tasks 任务主表
-- ------------------------------------------------------------
CREATE TABLE tasks (
    id                            TEXT PRIMARY KEY,
    youtube_url                   TEXT        NOT NULL,
    upload_target                 TEXT        NOT NULL DEFAULT 'acfun',
    status                        TEXT        NOT NULL DEFAULT 'pending',

    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),

    video_title_original          TEXT,
    video_title_translated        TEXT,
    description_original          TEXT,
    description_translated        TEXT,
    tags_generated                JSONB,

    recommended_partition_id      TEXT,
    selected_partition_id         TEXT,
    recommended_partition_id_acfun    TEXT,
    selected_partition_id_acfun       TEXT,
    recommended_partition_id_bilibili TEXT,
    selected_partition_id_bilibili    TEXT,

    cover_path_local              TEXT,
    video_path_local              TEXT,
    subtitle_path_original        TEXT,
    subtitle_path_translated      TEXT,
    subtitle_language_detected    TEXT,

    subtitle_qc_failed            BOOLEAN     NOT NULL DEFAULT FALSE,
    subtitle_qc_reason            TEXT,
    subtitle_qc_score             DOUBLE PRECISION,
    subtitle_qc_checked_at        TIMESTAMPTZ,

    metadata_json_path_local      TEXT,
    moderation_result             JSONB,

    error_message                 TEXT,
    error_category                TEXT,
    pipeline_checkpoint           JSONB,
    upload_progress               TEXT,

    acfun_upload_response         JSONB,
    bilibili_upload_response      JSONB,
    douyin_upload_response        JSONB,

    asr_warning_message           TEXT,
    subtitle_warning_message      TEXT,
    subtitle_quality_state        TEXT
);

CREATE INDEX idx_tasks_status       ON tasks (status);
CREATE INDEX idx_tasks_updated_at   ON tasks (updated_at DESC);
CREATE INDEX idx_tasks_upload_target ON tasks (upload_target);
CREATE INDEX idx_tasks_created_at   ON tasks (created_at DESC);

CREATE TRIGGER trg_tasks_updated_at
    BEFORE UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION y2a_set_updated_at();

COMMENT ON COLUMN tasks.upload_target IS 'acfun | bilibili | douyin | both';
COMMENT ON COLUMN tasks.status IS 'pending/downloading/.../completed/failed';
COMMENT ON COLUMN tasks.pipeline_checkpoint IS '已完成阶段列表，用于断点续跑';

-- ------------------------------------------------------------
-- app_config 配置表（208 项，按分类分组）
-- ------------------------------------------------------------
CREATE TABLE app_config (
    config_key   TEXT PRIMARY KEY,
    config_value JSONB       NOT NULL,
    category     TEXT        NOT NULL DEFAULT 'general',
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_config_category ON app_config (category);

-- ------------------------------------------------------------
-- monitor_configs 监控配置
-- ------------------------------------------------------------
CREATE TABLE monitor_configs (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT        NOT NULL,
    config_json     JSONB       NOT NULL,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    last_run_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_monitor_configs_updated_at
    BEFORE UPDATE ON monitor_configs
    FOR EACH ROW EXECUTE FUNCTION y2a_set_updated_at();

-- ------------------------------------------------------------
-- monitor_history 监控历史
-- ------------------------------------------------------------
CREATE TABLE monitor_history (
    id            BIGSERIAL PRIMARY KEY,
    config_id     BIGINT      NOT NULL REFERENCES monitor_configs(id) ON DELETE CASCADE,
    video_id      TEXT,
    title         TEXT,
    url           TEXT,
    published_at  TIMESTAMPTZ,
    task_created  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (config_id, video_id)
);
CREATE INDEX idx_monitor_history_config ON monitor_history (config_id, created_at DESC);

-- ------------------------------------------------------------
-- monitor_run_claim 监控运行锁
-- ------------------------------------------------------------
CREATE TABLE monitor_run_claim (
    config_id   BIGINT PRIMARY KEY REFERENCES monitor_configs(id) ON DELETE CASCADE,
    claimed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by  TEXT        NOT NULL
);

-- ------------------------------------------------------------
-- monitor_quota_budget / usage API 配额
-- ------------------------------------------------------------
CREATE TABLE monitor_quota_budget (
    quota_day   DATE PRIMARY KEY,
    budget      INTEGER NOT NULL DEFAULT 10000
);
CREATE TABLE monitor_quota_usage (
    quota_day   DATE        NOT NULL,
    api_key     TEXT        NOT NULL,
    used        INTEGER     NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (quota_day, api_key)
);

-- ------------------------------------------------------------
-- notification_outbox 通知异步队列
-- ------------------------------------------------------------
CREATE TABLE notification_outbox (
    id           BIGSERIAL PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    channel      TEXT,
    status       TEXT        NOT NULL DEFAULT 'pending',
    attempts     INTEGER     NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON notification_outbox (status, next_retry_at)
    WHERE status = 'pending';
