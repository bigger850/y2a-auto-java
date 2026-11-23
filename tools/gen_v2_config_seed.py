#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Python 版 config_manager.DEFAULT_CONFIG 生成 Flyway V2 种子脚本。

用法（在 Y2A-Auto 仓库根目录执行）::

    python ../y2a-java/tools/gen_v2_config_seed.py \
        > ../y2a-java/y2a-server/src/main/resources/db/migration/V2__seed_config.sql

目的：208 个配置项单靠手写一定会漂移，直接从未迁移前的权威源生成一次，
之后配置变更走 Flyway 增量脚本（V3、V4...），不再手改 V2。
"""
import ast
import json
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / "Y2A-Auto" / "modules" / "config_manager.py"


def load_defaults() -> dict:
    """在隔离命名空间里求值模块级常量赋值，解析 DEFAULT_CONFIG 中的变量引用。

    不 import 真实模块（会触发 torch 等重依赖），只执行纯常量赋值表达式。
    """
    tree = ast.parse(SRC.read_text(encoding="utf-8"), filename=str(SRC))
    namespace = {}
    default_node = None
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name == "DEFAULT_CONFIG":
                # 只取第一个 dict 字面量；后面 inject_speech_pipeline_defaults(...) 是函数调用
                if default_node is None and isinstance(node.value, ast.Dict):
                    default_node = node.value
            else:
                try:
                    namespace[name] = eval(compile(ast.Expression(node.value), str(SRC), "eval"),
                                           {"__builtins__": {}}, namespace)
                except Exception:
                    pass
    if default_node is None:
        raise RuntimeError("DEFAULT_CONFIG not found")
    return eval(compile(ast.Expression(default_node), str(SRC), "eval"),
                {"__builtins__": {}}, namespace)


def category_of(key: str) -> str:
    k = key.upper()
    if k.startswith(("YOUTUBE_",)) or "COOKIES" in k or "COOKIECLOUD" in k:
        return "account"
    if k.startswith(("OPENAI_", "AI_", "FALLBACK_", "SUBTITLE_OPENAI_")) or "REASONING" in k:
        return "ai"
    if k.startswith(("SUBTITLE_", "ASR_", "WHISPER_", "VOXTRAL_", "VAD_", "AUDIO_", "SPEECH_")):
        return "subtitle_asr"
    if k.startswith(("VIDEO_", "FFMPEG_")):
        return "media"
    if k.startswith("NOTIFY_"):
        return "notification"
    if k.startswith(("LOGIN_", "PASSWORD", "TG_BOT", "AUTO_MODE")) or "password" in k.lower():
        return "security"
    if k.startswith(("MAX_CONCURRENT_", "STUCK_", "PENDING_", "LOG_CLEANUP", "DOWNLOAD_CLEANUP", "DELETE_DOWNLOAD")):
        return "ops"
    if k.startswith(("CONTENT_MODERATION_", "ALIYUN_")):
        return "moderation"
    return "general"


def pg_json(value) -> str:
    # JSONB 字面量用单引号包裹，内部单引号需转义
    return "'" + json.dumps(value, ensure_ascii=False).replace("'", "''") + "'::jsonb"


# Java 版有意偏离 Python 默认值的配置项。
# AI 默认端点改为 DeepSeek（OpenAI 兼容协议），只需填 API Key 即可用。
#
# ⚠ 注意：V2__seed_config.sql 已在生产库应用，其 checksum 不可再变。
# 本 OVERRIDES 仅用于「全新环境重建 V2」或生成参考快照；
# 已有部署的默认值切换由 V4__ai_default_deepseek.sql 增量迁移完成。
OVERRIDES = {
    "OPENAI_BASE_URL": "https://api.deepseek.com",
    "OPENAI_MODEL_NAME": "deepseek-chat",
}


def main():
    defaults = load_defaults()
    defaults.update(OVERRIDES)
    print("-- Flyway V2: 从 Python DEFAULT_CONFIG 生成的默认配置种子（共 %d 项）" % len(defaults))
    print("-- 由 tools/gen_v2_config_seed.py 生成，请勿手工编辑")
    print("-- 注意：AI 默认端点已按 Java 版需求覆盖为 DeepSeek（见 OVERRIDES）")
    print("INSERT INTO app_config (config_key, config_value, category) VALUES")
    rows = []
    for key in sorted(defaults):
        rows.append("    ('%s', %s, '%s')" % (key, pg_json(defaults[key]), category_of(key)))
    print(",\n".join(rows) + ";")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
