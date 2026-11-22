#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抽取原版 Flask 模板中所有 <style> 块，合成 Vue 复刻用的 legacy-inline.css。"""
import re
from pathlib import Path

BASE = Path(__file__).resolve().parents[2] / "Y2A-Auto" / "templates"
OUT = Path(__file__).resolve().parents[1] / "y2a-web" / "public" / "css" / "legacy-inline.css"

TEMPLATES = [
    "index.html", "tasks.html", "manual_review.html",
    "youtube_monitor.html", "youtube_monitor_config.html",
    "youtube_monitor_history.html", "settings.html", "login.html", "edit_task.html",
]

chunks = ["/* 从原版 Flask 模板抽取的内联样式，1:1 复刻，勿随意修改 */\n"]
for name in TEMPLATES:
    path = BASE / name
    if not path.exists():
        continue
    text = path.read_text(encoding="utf-8")
    blocks = re.findall(r"<style[^>]*>(.*?)</style>", text, flags=re.S | re.I)
    for i, block in enumerate(blocks):
        chunks.append(f"\n/* ===== {name} <style>#{i + 1} ===== */\n{block.strip()}\n")

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(chunks), encoding="utf-8")
print(f"已生成 {OUT} ({OUT.stat().st_size} bytes)")
