# Quyết định triển khai TaskPilot

## Tài khoản và quyền truy cập

- Dùng ID người dùng nội bộ cho phiên và truy cập dữ liệu. Kiểm tra quyền hiện hành ở backend khi thực hiện thao tác, kể cả khi người dùng đã mở UI hoặc giữ kết nối từ trước.
- Xử lý riêng trạng thái tài khoản, liên kết danh tính OAuth, đặt mật khẩu và khôi phục. Liên kết nhà cung cấp theo hành động xác nhận của chủ tài khoản. Giao dịch thay đổi thành viên phải giữ được người quản lý đủ điều kiện.
- Gắn quyền vào dự án và tài nguyên cụ thể cho file, chat, SSE, thông báo và hành động AI. Hành động AI ghi dữ liệu cần xác nhận đúng thao tác đã xem trước.

## Task, sprint và dữ liệu Adaptive

- Dùng cùng quy tắc nghiệp vụ khi sửa task từ UI, API hoặc công cụ AI. Mang theo phiên bản quan sát và ID yêu cầu để kiểm soát cập nhật đồng thời và thử lại.
- Lưu lịch sử chốt sprint, nộp bài, duyệt và hủy hiệu lực đánh giá. Đánh giá độc lập có điểm chất lượng từ 1 đến 5. Ghi nhận tự xác nhận theo quy trình riêng.
- Tải công việc lấy từ công việc đang hoạt động trong dự án. Tính hiệu suất từ đánh giá hợp lệ và lưu nguồn dữ liệu. Với dữ liệu thiếu, hiển thị đúng trạng thái và dùng giá trị mặc định theo quy tắc chấm hiện hành.
- P8 lưu dữ liệu ứng viên tại thời điểm gợi ý và liên kết lựa chọn thực tế với lần giao task. Tập mẫu theo dự án, quản lý và chế độ. Kết quả duyệt về sau dùng để xác nhận dữ liệu đủ điều kiện và báo cáo kết quả của người được giao. Lượt từ chối mà chưa chọn người khác được lưu dưới dạng phản hồi. Hủy đánh giá loại mẫu khỏi tập đủ điều kiện.

## Cộng tác và ghi hình

- Truy cập tệp qua TaskPilot kiểm tra quyền dự án. Dùng thông tin kết nối Drive qua cấu hình môi trường.
- Xác nhận kết nối và đóng phòng qua LiveKit. Ghi hình theo nguồn người dùng chọn trên trình duyệt, theo dõi trạng thái lưu tệp và kiểm tra mở được bản ghi từ máy khác trong nghiệm thu.
- Theo dõi riêng bằng chứng từ test cô lập và UAT với hạ tầng thật trong [trạng thái bàn giao](STATUS.md).
