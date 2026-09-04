---
name: biweekly-progress-tracker
description: Track, log, and analyze engineering progress, timestamps, technical rationale, and impact metrics to generate standardized bi-weekly progress reports for university capstone (SE121/SE122) and project reviews.
---

# Bi-Weekly Progress Tracker & Impact Reporter Skill

This skill standardizes how engineering work, milestones, timestamps, and architectural/business impacts are tracked and compiled into high-quality **Bi-Weekly Progress Reports** (Báo cáo tiến độ mỗi 2 tuần) for academic evaluation (UIT Đồ án 2 / KLTN) and project stakeholders.

---

## 🎯 Core Capabilities

1. **Continuous Milestone & Work Logging**:
   - Captures what was done (`What was done`): features, refactors, bug fixes, devops setup, tests.
   - Logs precise timestamps, dates, sprint intervals, and associated Git commits.
   - Associates each task with affected modules (BE: `taskpilot-projects`, `taskpilot-users`, FE: `pages/`, `services/`, DevOps: `Dockerfile`, HF Spaces).

2. **Impact & Rationale Articulation (Ý nghĩa & Tác dụng kỹ thuật)**:
   - Evaluates *why* a change was made, *what problem it solves*, and *what value it creates*.
   - Quantifies impact with concrete metrics: lines of boilerplate removed (-LOC), memory savings, build time improvements, test coverage pass rate (100%), failure modes mitigated.
   - Maps changes back to the Project Proposal / Đề cương chi tiết (P0 Refactor, P1 RAG, P2 Teams-clone, Observability).

3. **Automated Bi-Weekly Report Generation**:
   - Provides automated scripts (`generate_biweekly_report.py`) that scan Git commits across `taskpilot`, `taskpilot-frontend`, and `report`.
   - Formats raw Git changes into professional Vietnamese academic sections matching UIT Đồ án 2 regulations (Phụ lục 2).
   - Generates ready-to-use Markdown and Word/Typst copy-paste sections.

4. **Multi-Repo Synchronization & Version Control**:
   - Ensures all tracking logs, scripts, and skills are tracked and pushed to GitHub across repositories.

---

## 📋 Standard Workflow for AI Agents & Developers

### Step 1: Record During or After Engineering Sessions
Whenever a task, refactor, or fix is completed:
```bash
python3 progress-tracker/scripts/log_milestone.py \
  --task "Refactor Backend Controllers using ApiResponse helpers" \
  --category "BE" \
  --impact "Loại bỏ 397 dòng boilerplate, chuẩn hóa mã HTTP response, tăng tốc độ bảo trì" \
  --metrics "-397 LOC, 100% compile pass"
```

### Step 2: Generate Bi-Weekly Report Draft
At the end of every 2-week cycle (e.g. Week 1-2, Week 3-4):
```bash
python3 progress-tracker/scripts/generate_biweekly_report.py \
  --period "2026-W35-W36" \
  --start-date "2026-08-25" \
  --end-date "2026-09-08"
```
The script outputs a comprehensive report in `progress-tracker/logs/period-YYYY-Wxx-Wyy.md`.

### Step 3: Review & Articulate Impact
Review the generated report against [Impact Categorization Guide](references/impact-rubric.md):
- Ensure every technical decision has clear rationale (e.g., why UID 1000 was enforced for Hugging Face Spaces).
- Verify metrics and test results.
- Document blockers encountered and solutions applied.

### Step 4: Update Master Index & Push to GitHub
Update `progress-tracker/PROGRESS_INDEX.md` and commit:
```bash
git add progress-tracker/ .agents/skills/biweekly-progress-tracker/
git commit -m "docs(progress): update bi-weekly progress log for period [Period-Name]"
git push origin main
```

---

## 📂 Directory Layout

```
progress-tracker/
├── README.md                      # Overview and instructions
├── PROGRESS_INDEX.md              # Master timeline table of all bi-weekly periods
├── templates/
│   └── biweekly-report-template.md# Full UIT-compliant report template
├── logs/
│   ├── period-2026-w35-w36.md     # Bi-weekly log for current period
│   └── ...                        # Historical logs
└── scripts/
    ├── generate_biweekly_report.py# Git history scanner and report generator
    └── log_milestone.py           # Quick CLI milestone logger
```

---

## 📑 Report Structure Standard (UIT Format)

Each bi-weekly report must follow the standardized structure:
1. **Thông tin chung kỳ báo cáo**: Mốc thời gian (Từ ngày - Đến ngày), Thành viên thực hiện, Đợt báo cáo số X.
2. **Mục tiêu giai đoạn (Sprint Goal)**: Đối chiếu với đề cương chi tiết (P0, P1, P2).
3. **Chi tiết công việc đã thực hiện (What Was Done)**: Bảng thống kê công việc, module, commit hash, thời gian.
4. **Ý nghĩa, tác dụng & Giá trị kỹ thuật (Impact & Rationale)**: Phân tích sâu về mặt kỹ thuật và giá trị dự án.
5. **Khó khăn, vướng mắc & Cách giải quyết (Challenges & Solutions)**: Các lỗi runtime, khác biệt môi trường, giải pháp.
6. **Kế hoạch cho 2 tuần tiếp theo (Next 2-Week Plan)**: Đầu việc cụ thể, phân công và thời hạn dự kiến.
7. **Minh chứng kỹ thuật (Evidence)**: Log build, test pass, benchmark, screenshot giao diện.
