#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
webnovel-writer LxChat adapter (thin, MIT).

A thin JSON bridge that lets LxChat's assistant drive the GPL-3.0 webnovel-writer
engine (https://github.com/lingfengQAQ/webnovel-writer) as an independent process.
This file is original code written for LxChat and is licensed under MIT; it does NOT
modify or inline webnovel-writer's GPL source. webnovel-writer runs unmodified and is
invoked via its own CLI entry `scripts/webnovel.py`.

Protocol (stable):
    adapter.py --action <action> --params <json>
    stdout: single JSON line { "ok": bool, "data": {...} | "error": str }

Actions: init / plan / write / review / query.

Model key injection: LxChat injects LCHAT_LLM_* (and INKOS_LLM_* for compat) env vars
from the user's configured default model service. webnovel-writer reads EMBED_*/RERANK_*
from its `.env`; when no embedding key is present it already falls back to BM25, so RAG
is optional and never blocks the writing pipeline. This adapter copies the configured LLM
credentials into the project's `.env` so the engine's own agents can use the user's key.

Designed for mobile: no bundled console spam, --quiet default, short progress only.
"""
import argparse
import json
import os
import subprocess
import sys

PROJECT_TYPES = ("init", "plan", "write", "review")
ALL_TYPES = ("query",) + PROJECT_TYPES

# Environment variable names LxChat may inject (LCHAT_* is primary, INKOS_* kept for compat).


def _env(*names):
    for n in names:
        v = os.environ.get(n)
        if v:
            return v
    return None


def out(payload):
    # Single structured JSON line on stdout; everything else is quiet.
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def err(etype, msg):
    out({"ok": False, "error": etype, "message": msg})


def engine_script():
    """Locate webnovel-writer's own CLI entry inside this package. Absent -> error."""
    root = os.environ.get("RUNTIME_ROOT") or os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(root, "webnovel-writer", "scripts", "webnovel.py"),
        os.path.join(root, "scripts", "webnovel.py"),
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def ensure_project(project_root):
    if not project_root:
        return None, "缺少项目根目录，请在 params.project_root 指定"
    os.makedirs(project_root, exist_ok=True)
    return project_root, None


def write_env_if_model(project_root):
    """Copy LxChat's configured model key into the project's .env if present."""
    base = _env("LCHAT_LLM_BASE_URL", "INKOS_LLM_BASE_URL")
    key = _env("LCHAT_LLM_API_KEY", "INKOS_LLM_API_KEY")
    model = _env("LCHAT_LLM_MODEL", "INKOS_LLM_MODEL")
    provider = _env("LCHAT_LLM_PROVIDER", "INKOS_LLM_PROVIDER")
    if not key:
        return
    env_path = os.path.join(project_root, ".env")
    existing = ""
    if os.path.isfile(env_path):
        with open(env_path, "r", encoding="utf-8", errors="ignore") as f:
            existing = f.read()
    lines = []
    seen = set()
    for line in existing.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key_name = line.split("=", 1)[0].strip()
        if key_name and key_name not in seen:
            lines.append(key_name + "=" + line.split("=", 1)[1].strip())
            seen.add(key_name)
    if provider:
        lines.append("LCHAT_LLM_PROVIDER=" + provider)
    if base:
        lines.append("LCHAT_LLM_BASE_URL=" + base)
    if key:
        lines.append("LCHAT_LLM_API_KEY=" + key)
    if model:
        lines.append("LCHAT_LLM_MODEL=" + model)
    with open(env_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def run_engine(script, project_root, subcommand, args=None):
    """Invoke webnovel-writer's own CLI for a fine-grained subcommand."""
    cmd = ["python", "-X", "utf8", script, "--project-root", project_root]
    if subcommand:
        cmd.append(subcommand)
    if args:
        cmd.extend(args)
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        return None, "引擎执行超时", True
    except Exception as exc:  # noqa: BLE001
        return None, "引擎执行异常: %s" % exc, False
    return proc.stdout + proc.stderr, None, False


def main():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--action", required=True)
    parser.add_argument("--params", default="")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    action = (args.action or "").strip().lower()
    if action not in ALL_TYPES:
        err("bad_action", "未知 action：%s，支持 %s" % (action, "/".join(ALL_TYPES)))
        return

    # PARSE params (opaque JSON string, tolerant of empty/external fields).
    try:
        p = json.loads(args.params) if args.params else {}
        if not isinstance(p, dict):
            p = {"payload": p}
    except json.JSONDecodeError:
        p = {"raw": args.params}

    project_root = os.environ.get("RUNTIME_PROJECT_ROOT") or p.get("project_root") or ""
    project_root, perr = ensure_project(project_root)
    if perr:
        err("no_project", perr)
        return

    script = engine_script()
    if not script:
        # Engine source not yet installed alongside the adapter.
        err(
            "upstream_missing",
            "适配器已就绪，但未发现 webnovel-writer 上游源码（scripts/webnovel.py 缺失）。"
            "请确认引擎包完整安装（含 webnovel-writer 源码树）。",
        )
        return

    write_env_if_model(project_root)

    # init/plan/write/review require a configured LLM key; without it the engine cannot create.
    if action in PROJECT_TYPES and not _env("LCHAT_LLM_API_KEY", "INKOS_LLM_API_KEY"):
        err("model_not_configured", "未配置模型 Key，无法进行创作。请先在设置中配置默认模型服务。")
        return

    # Map high-level action onto webnovel-writer's CLI subcommands.
    mapping = {
        "init": ["preflight", "story-system"],
        "plan": ["story-system", "chapter-commit"],
        "write": ["write-gate", "story-system", "chapter-commit"],
        "review": ["story-system"],
        "query": ["story-events"],
    }
    subcommands = mapping.get(action, [])

    results = []
    ok = True
    for sc in subcommands:
        target = p.get("target") or p.get("chapter") or p.get("scope")
        extra = []
        if target:
            extra = [str(target)]
        text, run_err, timed = run_engine(script, project_root, sc, extra)
        results.append({"subcommand": sc, "ok": run_err is None, "timed_out": timed,
                        "output": ("" if run_err else text),
                        "error": run_err})
        if run_err is not None:
            ok = False

    if ok:
        out({"ok": True, "data": {"action": action, "steps": results,
                                   "rag": "degraded-or-bm25" if not _env("EMBED_API_KEY") else "enabled"}})
    else:
        err("engine_partial", "引擎步骤部分失败，详见 steps；可运行 webnovel query 查看完整性。"
            if any(r["timed_out"] for r in results) else "引擎步骤执行失败")


if __name__ == "__main__":
    main()