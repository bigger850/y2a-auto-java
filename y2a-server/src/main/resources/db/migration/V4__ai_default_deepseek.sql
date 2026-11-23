-- ============================================================
-- V4: AI 默认端点切换为 DeepSeek
--
-- 背景：默认 AI 配置由 OpenAI 改为 DeepSeek（OpenAI 兼容协议）。
-- 策略：只更新「仍是旧 OpenAI 默认值」且「用户未改动」的记录，
--       已填写自定义端点的部署不受影响。
-- ============================================================

-- 仅在当前值精确等于旧默认值时才替换，避免覆盖用户已配置的端点
UPDATE app_config
SET config_value = '"https://api.deepseek.com"'::jsonb,
    updated_at = now()
WHERE config_key = 'OPENAI_BASE_URL'
  AND config_value = '"https://api.openai.com/v1"'::jsonb;

UPDATE app_config
SET config_value = '"deepseek-chat"'::jsonb,
    updated_at = now()
WHERE config_key = 'OPENAI_MODEL_NAME'
  AND config_value = '"gpt-3.5-turbo"'::jsonb;

-- 补充：新增思考相关的默认值（DeepSeek 目前不暴露 reasoning_effort，保持 auto 即可）
INSERT INTO app_config (config_key, config_value, category)
VALUES ('AI_DEFAULT_PROVIDER', '"deepseek"'::jsonb, 'ai')
ON CONFLICT (config_key) DO NOTHING;
