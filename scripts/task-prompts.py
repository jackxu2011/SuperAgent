#!/usr/bin/env python3
"""Shows, turn by turn, what a SuperAgent task actually sent to the model.

SuperAgent itself keeps no copy of the prompts; the CLIs do. A coding task runs in
<data>/tasks/<company>/code/<task id>, and each CLI records its conversations by working directory:
  Codex:       ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl (first line: session_meta with cwd);
               the platform's system prompt is in each message as <system>...</system>
  Claude Code: ~/.claude/projects/<cwd with separators as '-'>/<session>.jsonl;
               the system prompt goes in --append-system-prompt, so only the server log has it

  scripts/task-prompts.py <task id or prefix>              one line per turn
  scripts/task-prompts.py <task id> --show 3               turn 3 in full (system block, instruction, reply)
  scripts/task-prompts.py <task id> --dump /tmp/prompts    every turn as its own Markdown file
"""
import argparse
import json
import os
import re
import signal
import sys
from datetime import datetime
from pathlib import Path

# Opening phrases of CodingPrompts / CodingHarness prompts -> the step they belong to (first match wins).
STEPS = [
    ("只看这次【解决冲突】做得对不对", "冲突审查"),
    ("当前是【规格】这一步", "规格"),
    ("上一版缺少可检查的「## 验收标准」", "规格补格式"),
    ("对照下面的 PRD（任务说明）和规格", "规格核对"),
    ("现在是【开发】这一步", "开发"),
    ("平台在工作区里跑了验证命令", "验证修复"),
    ("你是这个编码任务的独立审查人", "审查"),
    ("你是这个编码任务的验收人", "验收"),
    ("验收认为代码没问题，只是交付说明缺", "补交付说明"),
    ("发现了下面这些问题", "按意见修复"),
    ("现在是【解决冲突】这一步", "解决冲突"),
    ("【解决冲突】第", "冲突修复"),
]
SYSTEM_RE = re.compile(r"<system>\s*(.*?)\s*</system>\s*", re.S)


def step_of(text):
    for marker, step in STEPS:
        if marker in text:
            return step
    if text.startswith("# AGENTS.md instructions"):
        return "CLI 注入 AGENTS.md"
    if text.startswith("<environment_context>"):
        return "CLI 环境信息"
    return "其他"


def sections(system):
    """What the system block is made of: its '## ' headings and 【…】 rules, in order, with the size of each.
    The writing style guide has '## ' headings of its own; they fold into it until the next 【…】 rule."""
    found = []  # [name, chars]
    in_guide = False
    for raw in system.splitlines():
        line = raw.strip()
        heading = line[3:].split("（")[0].strip() if line.startswith("## ") else None
        rule = line[: line.index("】") + 1] if line.startswith("【") and "】" in line else None
        if rule and not (found and found[-1][0] == "项目共同上下文"):  # the project's own rule stays in its context
            in_guide = False
            found.append([rule, 0])
        elif heading and not in_guide:
            in_guide = heading == "文档写作规范"
            found.append([heading, 0])
        if found:
            found[-1][1] += len(raw) + 1
    return [f"{name}({size})" for name, size in found]


def ts(value):
    """Codex and Claude record UTC; show local time."""
    if not value:
        return ""
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone().strftime("%Y-%m-%d %H:%M:%S")
    except ValueError:
        return value[:19].replace("T", " ")


# ---------- Codex ----------

def codex_conversations(codex_home, task):
    root = Path(codex_home).expanduser() / "sessions"
    for f in sorted(root.glob("*/*/*/rollout-*.jsonl")):
        try:
            with open(f, encoding="utf-8", errors="replace") as fh:
                meta = json.loads(fh.readline())
        except (OSError, ValueError):
            continue
        cwd = (meta.get("payload") or {}).get("cwd") or ""
        if task in os.path.basename(cwd.rstrip("/")) or ("/" + task) in cwd:
            yield f, meta.get("payload", {}).get("id", f.stem)[:8], cwd


def codex_turns(path, conv):
    """Each user message, with the assistant text that followed it."""
    turns = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except ValueError:
                continue
            p = o.get("payload") or {}
            if p.get("type") != "message":
                continue
            text = "\n".join(c.get("text", "") for c in p.get("content") or [] if c.get("text"))
            if p.get("role") == "user" and text:
                turns.append({"cli": "codex", "conv": conv, "at": o.get("timestamp"), "text": text, "reply": ""})
            elif p.get("role") == "assistant" and text and turns:
                turns[-1]["reply"] = text  # the last one before the next user message is the turn's answer
    return turns


# ---------- Claude Code ----------

def claude_conversations(claude_dir, task):
    root = Path(claude_dir).expanduser() / "projects"
    if not root.is_dir():
        return
    for d in sorted(root.iterdir()):
        if d.is_dir() and task in d.name:
            for f in sorted(d.glob("*.jsonl")):
                yield f, f.stem[:8], d.name


def claude_turns(path, conv):
    turns = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            try:
                o = json.loads(line)
            except ValueError:
                continue
            msg = o.get("message") or {}
            content = msg.get("content")
            if isinstance(content, str):
                text = content
            else:
                text = "\n".join(c.get("text", "") for c in content or [] if isinstance(c, dict) and c.get("type") == "text")
            if o.get("type") == "user" and text and not o.get("isMeta"):
                turns.append({"cli": "claude", "conv": conv, "at": o.get("timestamp"), "text": text, "reply": ""})
            elif o.get("type") == "assistant" and text and turns:
                turns[-1]["reply"] = text
    return turns


# ---------- output ----------

def enrich(turn):
    m = SYSTEM_RE.match(turn["text"])
    turn["system"] = m.group(1) if m else ""
    turn["instruction"] = turn["text"][m.end():] if m else turn["text"]
    turn["step"] = step_of(turn["instruction"])
    return turn


def first_line(text, width=46):
    line = next((l.strip() for l in text.splitlines() if l.strip()), "")
    return line if len(line) <= width else line[:width] + "…"


def render(turn, n):
    parts = [f"# 第 {n} 轮 · {turn['step']} · {turn['cli']} 会话 {turn['conv']} · {ts(turn['at'])}", ""]
    if turn["system"]:
        parts += [f"## 系统块（{len(turn['system'])} 字）", "", turn["system"], ""]
    elif turn["cli"] == "claude":
        parts += ["## 系统块", "", "（Claude Code 的系统提示通过 --append-system-prompt 传入，不在会话记录里；"
                  "到服务日志里按 `running: claude` 找对应时间的那一行）", ""]
    parts += [f"## 指令（{len(turn['instruction'])} 字）", "", turn["instruction"], ""]
    if turn["reply"]:
        parts += [f"## 模型回复（最后一条，{len(turn['reply'])} 字）", "", turn["reply"], ""]
    return "\n".join(parts)


def main():
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)  # quiet when piped into head / less
    ap = argparse.ArgumentParser(description="逐轮查看 SuperAgent 任务实际发给模型的提示词")
    ap.add_argument("task", help="任务 ID 或其前缀（至少 8 位）")
    ap.add_argument("--show", type=int, metavar="N", help="完整打印第 N 轮")
    ap.add_argument("--dump", metavar="DIR", help="每轮写成一个 Markdown 文件")
    ap.add_argument("--codex-home", default=os.environ.get("CODEX_HOME", "~/.codex"))
    ap.add_argument("--claude-dir", default=os.environ.get("CLAUDE_CONFIG_DIR", "~/.claude"))
    args = ap.parse_args()
    if len(args.task) < 8:
        sys.exit("任务 ID 至少给 8 位，免得匹配到别的任务")

    turns, convs = [], []
    for path, conv, where in codex_conversations(args.codex_home, args.task):
        convs.append(f"codex {conv}  {path}")
        turns += codex_turns(path, conv)
    for path, conv, where in claude_conversations(args.claude_dir, args.task):
        convs.append(f"claude {conv}  {path}")
        turns += claude_turns(path, conv)
    if not turns:
        sys.exit(f"没找到任务 {args.task} 的会话记录（找过 {args.codex_home}/sessions 和 {args.claude_dir}/projects）。"
                 "只有编码任务有自己的工作目录；普通任务和对话走的是共享会话。")
    turns = [enrich(t) for t in sorted(turns, key=lambda t: t["at"] or "")]

    if args.show:
        if not 1 <= args.show <= len(turns):
            sys.exit(f"只有 {len(turns)} 轮")
        print(render(turns[args.show - 1], args.show))
        return
    if args.dump:
        out = Path(args.dump)
        out.mkdir(parents=True, exist_ok=True)
        for n, t in enumerate(turns, 1):
            (out / f"{n:02d}-{t['step'].replace(' ', '-')}.md").write_text(render(t, n), encoding="utf-8")
        print(f"已写出 {len(turns)} 个文件到 {out}")
        return

    print(f"任务 {args.task}：{len(convs)} 个会话，{len(turns)} 轮")
    for c in convs:
        print("  " + c)
    print()
    print(f"{'#':>2}  {'时间':<19}  {'会话':<8}  {'步骤':<10}  {'系统块':>6}  {'指令':>6}  系统块组成 / 指令开头")
    for n, t in enumerate(turns, 1):
        made = " / ".join(sections(t["system"])) or "-"
        print(f"{n:>2}  {ts(t['at']):<19}  {t['conv']:<8}  {t['step']:<10}  {len(t['system']):>6}  {len(t['instruction']):>6}  {made}")
        print(f"{'':>2}  {'':<19}  {'':<8}  {'':<10}  {'':>6}  {'':>6}  ↳ {first_line(t['instruction'])}")
    print("\n完整内容：--show N 打印某一轮，--dump DIR 全部导出")


if __name__ == "__main__":
    main()
