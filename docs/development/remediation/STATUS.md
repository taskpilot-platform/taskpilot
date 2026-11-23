# Trạng thái bàn giao P1–P8

Mốc mã nguồn đã kiểm chứng là backend `ba1e8a4` và frontend `ff3b71d` trên nhánh `fix/full-remediation` của mỗi repo.

| Phạm vi | Trạng thái |
| --- | --- |
| P1–P8 | Đã kiểm chứng trên môi trường cô lập. Mốc backend đã chạy PostgreSQL V51. Hai repo cần được review cùng nhau. |
| P4 và P7 với hạ tầng thật | Còn kiểm tra ACL Drive cũ, họp LiveKit đa điểm và mở bản ghi Drive từ máy khác. |
| P9 | Chờ giảng viên phản hồi về OGD trong đề cương và phương án học theo đợt. |
| P10–P11 | Hạng mục tiếp theo theo [kế hoạch](PLAN.md). Nhóm thống nhất người phụ trách khi nhận việc. |

Hồi quy tại mốc trên qua 670 test backend, 169 test Vitest, 173 ca Chromium cô lập và build frontend. Ca Chromium nối API thật đã kiểm tra thẻ gợi ý giao task, nộp và duyệt qua API, rồi thấy đúng một mẫu trong báo cáo Adaptive theo phạm vi. Phần trình bày của Copilot trong ca đó dùng dữ liệu mô phỏng.

Khi nhận một hạng mục, ghi mã phase, phạm vi và người phụ trách ở PR hoặc issue tương ứng. Khi bàn giao, cập nhật mốc hai repo, kết quả kiểm chứng và việc còn chờ.

## Bản sửa Copilot sau P8, kiểm chứng ngày 23/11/2026

Diff trên `fix/full-remediation` của hai repo đã được kiểm chứng với 697 test backend, 180 test frontend và build frontend. Lượt frontend mặc định có timeout ở test admin khi chạy đồng thời với backend và UAT. Chạy lại toàn bộ với `npm run test -- --maxWorkers=2` đã qua. Các test backend dùng PostgreSQL cô lập.

Copilot mặc định gọi Groq GPT-OSS 120B trước, rồi đến các Gemini đã qua probe và OpenRouter đã qua probe. Thứ tự quản trị viên đã lưu trong `ai.model_priority` được giữ. Sau khi deploy backend mới, quản trị viên có thể sao lưu setting rồi đặt `valueJson` thành `{"models":[]}` qua `PUT /api/v1/admin/settings` để dùng chuỗi mặc định. Khi khôi phục, ghi lại đúng giá trị đã sao lưu.

UAT Chromium với dữ liệu tổng hợp và DB V51 riêng đã kiểm tra giao việc qua API model thật và ca giả lập Groq lỗi sau khi tạo đề xuất để Gemini thật tiếp quản. Cả hai ca đều có đúng một thẻ xác nhận, task chưa được gán trước khi bấm và được gán đúng sau xác nhận. Đã kiểm tra đổi thứ tự theo setting và khôi phục setting cũ trong DB cô lập.

Test hồi quy bao phủ model gửi token rồi treo, loại phần trả lời dở khi đổi model, tool bỏ qua interrupt và hai tool cùng vòng trong đó đề xuất hoàn tất trước một tool bị treo. SSE kết thúc theo hạn lượt, giữ đề xuất đã ghi nhận và bỏ qua kết quả đến muộn. Tool bỏ qua interrupt có thể tiếp tục dọn dẹp sau khi SSE đóng.

Backend và frontend cần được review và triển khai cùng nhau do bổ sung mã trạng thái i18n và sự kiện `reset_stream`. Kết quả trên áp dụng cho diff đang bàn giao. Môi trường live chưa được cập nhật trong lần kiểm chứng này.
