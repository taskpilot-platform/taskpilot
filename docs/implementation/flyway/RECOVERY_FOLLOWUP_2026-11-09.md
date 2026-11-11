# Sau phát hành hotfix ứng dụng — 09/11/2026

> **Bản ghi lịch sử 09/11:** các kết luận dưới đây mô tả thời điểm điều tra/hotfix ban đầu. Ngày 10/11, nguồn đã tiếp nhận Flyway V42, validate đạt và app mới đã deploy/live smoke thành công; xem [kết quả phát hành cuối](RECOVERY_RELEASE_2026-11-10.md). Dùng [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md) cho migration mới, không lặp lại hướng recovery thử nghiệm cũ.

## Phần đã hoàn tất

- PR #51 đã merge; CI/test/Docker/CodeQL đạt. Workflow main đồng bộ Hugging Face thành công, runtime revision `7575d76800de460482d777e09a385237efbf5fde` chạy bản sửa.
- Lỗi 200 rỗng, email không nhất quán ở admin/OAuth/throttle và lỗi refresh 500 do detached Hibernate proxy đã được sửa. PostgreSQL regression test chứng minh lỗi refresh trước/sau; smoke lab kiểm tra thêm reset, admin, project/task và lịch họp.
- Kiểm tra trên dịch vụ thật: register 201, login/profile/refresh 200, duplicate 409 JSON, health UP. Đã dọn đúng tài khoản thử và refresh token của nó; số tài khoản nguồn trước/sau đều 458.
- Backup cấu trúc và toàn bộ dữ liệu `public` được lưu ở thư mục riêng giới hạn ACL, restore thành công vào PostgreSQL 17 không có mạng. Có 39 bảng, 458 user, 41 dòng history; unique index/email aggregates khớp. Container phục hồi có dữ liệu thật đã được xóa sau kiểm chứng.
- Digest history nguồn sau triển khai khớp history trong backup trước phát hành. Không baseline/repair/replay V41, không thay đổi RLS hay email tài khoản hiện có.

## Bằng chứng riêng, không đưa vào Git công khai

Thư mục `%LOCALAPPDATA%/TaskPilotRecovery/20261109-224853/`:

- `public-data-and-schema.dump` — bản backup có dữ liệu thật, không đính kèm PR.
- `manifest.json` — SHA-256, phạm vi, restore metadata và history digest.
- `source-metadata.json`, `github-pr-ci.log`, `release-provenance.json`, `live-smoke-results.json`.

SHA-256 dump: `a9e5fdc480231c2f7b6200206f7296bf212a1935e513cca63792f7586e2e88f1`.

Bằng chứng lab bổ sung tại thư mục lab cũ: `hotfix-prod-smoke-results.json`, `hotfix-prod-smoke.log`. Harness tạm `taskpilot-hotfix-prod-smoke.py` và `HttpProbe.class` chỉ kiểm tra Docker lab; không là mã ứng dụng phát hành.

## Phần còn mở

1. Backup vừa kiểm chứng chỉ bao gồm `public`, loại owner/ACL; chưa xác minh PITR trước V41 hoặc đầy đủ `auth`/`storage`/schema Supabase khác. Không dùng nó để khẳng định có thể khôi phục email gốc của chín tài khoản `_dup_`.
2. Metadata quyền nguồn vẫn cho thấy hai bảng `user_identities` và `project_meetings` tắt RLS, `anon`/`authenticated` có SELECT. Role kết nối được cấu hình là `postgres`, không superuser nhưng có BYPASSRLS và quyền owner. Chưa xác minh đầy đủ role của mọi consumer hoặc đường PostgREST; không tuyên bố đã đóng rủi ro phơi lộ.
3. Bộ history mới/snapshot vẫn cần review owner/ACL/seed, schema ngoài public, preflight đạt/trượt tường minh và migration numbering trước cutover. `application.yml` mặc định không được đổi trong hotfix.
4. Chín email hậu tố cần đối soát tài khoản và bằng chứng trước V41; không tự cắt hậu tố/gộp user. OAuth/AI/S3/LiveKit với credential thật chưa được UAT đầy đủ trong phiên này.

Ứng dụng đã nhận hotfix và vượt qua các luồng live kể trên. Việc này không đồng nghĩa toàn bộ sự cố lịch sử dữ liệu/quyền đã được khép lại.
