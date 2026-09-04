#!/usr/bin/env python3
"""
log_milestone.py
Quick CLI tool to log an engineering task or milestone into the current bi-weekly report.
"""

import argparse
from datetime import datetime
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent.parent
DEFAULT_PERIOD = "2026-W35-W36"

def log_milestone(period, task, category, impact, metrics, assignee):
    log_file = BASE_DIR / "progress-tracker" / "logs" / f"period-{period.lower()}.md"
    if not log_file.exists():
        print(f"[-] Report file {log_file} not found. Creating a new one from template...")
        template_file = BASE_DIR / "progress-tracker" / "templates" / "biweekly-progress-template.md"
        if template_file.exists():
            log_file.write_text(template_file.read_text(encoding="utf-8"), encoding="utf-8")
        else:
            log_file.write_text(f"# Bi-Weekly Log: {period}\n\n", encoding="utf-8")

    now_str = datetime.now().strftime("%Y-%m-%d %H:%M")
    entry_line = f"| - | **[{category}]** {task} | {impact} | {metrics} | {now_str} | {assignee} |\n"

    content = log_file.read_text(encoding="utf-8")
    table_marker = "## 2. BẢNG CHI TIẾT CÔNG VIỆC ĐÃ THỰC HIỆN (WHAT WAS DONE)"
    if table_marker in content:
        # Append before next section
        parts = content.split(table_marker, 1)
        next_section = parts[1].find("---")
        if next_section != -1:
            table_part = parts[1][:next_section]
            rest_part = parts[1][next_section:]
            updated_table = table_part.rstrip() + "\n" + entry_line + "\n"
            content = parts[0] + table_marker + updated_table + rest_part
        else:
            content += "\n" + entry_line
    else:
        content += f"\n### New Milestone ({now_str})\n- **Task**: {task}\n- **Impact**: {impact}\n- **Metrics**: {metrics}\n"

    log_file.write_text(content, encoding="utf-8")
    print(f"[+] Milestone logged successfully to {log_file}")

def main():
    parser = argparse.ArgumentParser(description="TaskPilot Milestone Quick Logger")
    parser.add_argument("--period", default=DEFAULT_PERIOD, help="Period name")
    parser.add_argument("--task", required=True, help="Task description")
    parser.add_argument("--category", default="Core", choices=["BE", "FE", "DevOps", "AI", "Docs", "Core"], help="Category")
    parser.add_argument("--impact", default="", help="Technical rationale & impact")
    parser.add_argument("--metrics", default="", help="Metrics (e.g. -150 LOC, 100% pass)")
    parser.add_argument("--assignee", default="Team", help="Assignee name")

    args = parser.parse_args()
    log_milestone(args.period, args.task, args.category, args.impact, args.metrics, args.assignee)

if __name__ == "__main__":
    main()
