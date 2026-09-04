# TaskPilot Bi-Weekly Progress Tracker Package

Gói công cụ và thư mục quản lý tiến độ thực hiện Đồ án 2 (SE121 - UIT).  
Tự động hóa theo dõi, ghi nhận mốc thời gian, phân tích ý nghĩa/tác dụng kỹ thuật và xuất báo cáo tiến độ 2 tuần một lần gửi Giảng viên hướng dẫn.

---

## 📁 Cấu trúc thư mục (Directory Structure)

```
progress-tracker/
├── README.md                      # Tài liệu hướng dẫn sử dụng gói
├── PROGRESS_INDEX.md              # Bảng mục lục tổng hợp tiến độ toàn bộ các đợt
├── templates/
│   └── biweekly-progress-template.md  # Biểu mẫu báo cáo chuẩn theo mẫu Đồ án UIT
├── logs/
│   ├── period-2026-w35-w36.md     # Báo cáo chi tiết đợt 1 (Tuần 35-36)
│   └── template.md                # Biểu mẫu trắng để nhân bản cho kỳ mới
└── scripts/
    ├── generate_biweekly_report.py# Tool tự động quét Git commit 3 repos & xuất báo cáo
    └── log_milestone.py           # Tool CLI ghi nhanh công việc vừa hoàn thành
```

---

## 🚀 Hướng Dẫn Sử Dụng

### 1. Ghi nhận nhanh một công việc vừa hoàn thành (Quick Milestone Logging)
Khi bạn hoặc AI Agent vừa hoàn thành một đợt refactor, viết xong tính năng, hoặc sửa lỗi:
```bash
python3 progress-tracker/scripts/log_milestone.py \
  --period "2026-W35-W36" \
  --task "Refactor Backend Controllers using ApiResponse helpers" \
  --category "BE" \
  --impact "Loại bỏ 397 dòng boilerplate, chuẩn hóa mã HTTP response, tăng tốc độ bảo trì" \
  --metrics "-397 LOC, 100% test pass"
```

### 2. Tự động quét Git commits và xuất dự thảo báo cáo 2 tuần (Auto-generate Bi-Weekly Report)
Vào ngày cuối của chu kỳ 2 tuần, chạy lệnh:
```bash
python3 progress-tracker/scripts/generate_biweekly_report.py \
  --period "2026-W35-W36" \
  --start-date "2026-08-25" \
  --end-date "2026-09-08"
```
Script sẽ tự động:
- Đọc git log từ cả 3 kho mã: `taskpilot` (Backend), `taskpilot-frontend` (Frontend), và `report` (Báo cáo).
- Phân loại commit (`refactor`, `feat`, `fix`, `docs`, `test`, `chore`).
- Thống kê tổng số dòng code thêm (+), bớt (-), tính toán LOC tối ưu.
- Điền sẵn vào mẫu báo cáo tại `progress-tracker/logs/period-YYYY-Wxx-Wyy.md`.

### 3. Cập nhật Bảng tổng quan tiến độ (Master Index)
Mỗi đợt báo cáo mới sẽ được ghi một dòng vào `PROGRESS_INDEX.md` để theo dõi xuyên suốt từ đầu kỳ (01/09/2026) đến cuối kỳ (01/01/2027).

### 4. Đồng bộ lên GitHub
Gói công cụ này được thiết kế để nằm trực tiếp trong kho mã nguồn Git (`taskpilot` và `report`), bạn chỉ cần chạy:
```bash
git add progress-tracker/ .agents/skills/biweekly-progress-tracker/
git commit -m "docs(progress): update bi-weekly progress logs"
git push origin main
```
Toàn bộ lịch sử tiến độ sẽ được lưu trữ an toàn trên GitHub!
