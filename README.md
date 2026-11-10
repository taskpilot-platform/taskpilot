---
title: TaskPilot
emoji: 🚀
colorFrom: blue
colorTo: green
sdk: docker
app_port: 7860
pinned: false
---

# TaskPilot - AI-powered Task Allocation System
Dự án Backend cho hệ thống quản lý tác vụ tích hợp AI.

## Làm việc với database chung

Trước khi tạo migration, chạy `python tools/flyway-team/check.py` để đọc version DB chung và đối chiếu main/local. Số đề xuất chưa được giữ chỗ: hai người chốt thứ tự qua PR và kiểm tra lại trước merge.

Đọc [quy trình Flyway cho nhóm](docs/development/FLYWAY_TEAM_WORKFLOW.md). Agent dùng [SKILL.md độc lập](.agents/skills/taskpilot-flyway-team/SKILL.md), không cần Superpowers; `AGENTS.md` ở gốc repo trỏ tới cùng quy tắc.
