-- ============================================================
-- V3: YouTube 监控模块表结构（对齐 Python youtube_monitor.py）
-- 注意：V1 里已建过一版简化的 monitor_* 表，这里先删除重建为完整结构
-- ============================================================

DROP TABLE IF EXISTS monitor_history CASCADE;
DROP TABLE IF EXISTS monitor_run_claim CASCADE;
DROP TABLE IF EXISTS monitor_quota_usage CASCADE;
DROP TABLE IF EXISTS monitor_quota_budget CASCADE;
DROP TABLE IF EXISTS monitor_configs CASCADE;

CREATE TABLE monitor_configs (
    id                        BIGSERIAL PRIMARY KEY,
    name                      TEXT        NOT NULL,
    enabled                   BOOLEAN     NOT NULL DEFAULT TRUE,

    region_code               TEXT        NOT NULL DEFAULT 'US',
    category_id               TEXT        NOT NULL DEFAULT '0',
    time_period               INTEGER     NOT NULL DEFAULT 7,
    max_results               INTEGER     NOT NULL DEFAULT 10,
    min_view_count            INTEGER     NOT NULL DEFAULT 0,
    min_like_count            INTEGER     NOT NULL DEFAULT 0,
    min_comment_count         INTEGER     NOT NULL DEFAULT 0,

    keywords                  TEXT        NOT NULL DEFAULT '',
    exclude_keywords          TEXT        NOT NULL DEFAULT '',
    title_keywords            TEXT        NOT NULL DEFAULT '',
    title_match_mode          TEXT        NOT NULL DEFAULT 'any',

    channel_ids               TEXT        NOT NULL DEFAULT '',
    channel_keywords          TEXT        NOT NULL DEFAULT '',
    exclude_channel_ids       TEXT        NOT NULL DEFAULT '',

    min_duration              INTEGER     NOT NULL DEFAULT 0,
    max_duration              INTEGER     NOT NULL DEFAULT 0,

    schedule_type             TEXT        NOT NULL DEFAULT 'manual',
    schedule_interval         INTEGER     NOT NULL DEFAULT 120,
    order_by                  TEXT        NOT NULL DEFAULT 'viewCount',
    start_date                TEXT        NOT NULL DEFAULT '',
    end_date                  TEXT        NOT NULL DEFAULT '',
    latest_days               INTEGER     NOT NULL DEFAULT 7,
    latest_max_results        INTEGER     NOT NULL DEFAULT 20,
    rate_limit_requests       INTEGER     NOT NULL DEFAULT 20,
    rate_limit_window         INTEGER     NOT NULL DEFAULT 60,

    auto_add_to_tasks         BOOLEAN     NOT NULL DEFAULT FALSE,
    monitor_type              TEXT        NOT NULL DEFAULT 'youtube_search',
    channel_mode              TEXT        NOT NULL DEFAULT 'latest',
    video_types               TEXT        NOT NULL DEFAULT 'video,short,live',

    historical_progress_date  TEXT        NOT NULL DEFAULT '',
    historical_offset         INTEGER     NOT NULL DEFAULT 0,

    last_run_time             TIMESTAMPTZ,
    created_time              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_time              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_monitor_configs_enabled ON monitor_configs (enabled);

CREATE TABLE monitor_history (
    id               BIGSERIAL PRIMARY KEY,
    config_id        BIGINT      NOT NULL REFERENCES monitor_configs(id) ON DELETE CASCADE,
    video_id         TEXT        NOT NULL,
    video_type       TEXT        NOT NULL DEFAULT '',
    video_title      TEXT        NOT NULL DEFAULT '',
    channel_title    TEXT        NOT NULL DEFAULT '',
    view_count       BIGINT      NOT NULL DEFAULT 0,
    like_count       BIGINT      NOT NULL DEFAULT 0,
    comment_count    BIGINT      NOT NULL DEFAULT 0,
    duration         TEXT        NOT NULL DEFAULT '',
    published_at     TEXT        NOT NULL DEFAULT '',
    added_to_tasks   BOOLEAN     NOT NULL DEFAULT FALSE,
    task_claimed_at  DOUBLE PRECISION,
    run_time         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_monitor_history_config_video UNIQUE (config_id, video_id)
);

CREATE INDEX idx_monitor_history_config_run ON monitor_history (config_id, run_time DESC);

CREATE TABLE monitor_run_claim (
    config_id    BIGINT             PRIMARY KEY REFERENCES monitor_configs(id) ON DELETE CASCADE,
    owner_token  TEXT               NOT NULL,
    claimed_at   DOUBLE PRECISION   NOT NULL
);

CREATE TABLE monitor_quota_budget (
    quota_date  DATE    PRIMARY KEY,
    budget      INTEGER NOT NULL DEFAULT 95
);

CREATE TABLE monitor_quota_usage (
    config_id   BIGINT  NOT NULL,
    quota_date  DATE    NOT NULL,
    used        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (config_id, quota_date)
);
