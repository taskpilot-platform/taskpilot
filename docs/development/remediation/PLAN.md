# Kế hoạch khắc phục TaskPilot

Hai repo `taskpilot` và `taskpilot-frontend` cùng theo kế hoạch này. Đọc [trạng thái bàn giao](STATUS.md) trước khi nhận một hạng mục và [quyết định triển khai](DESIGN.md) trước khi thay đổi hành vi hoặc hợp đồng giữa các module.

## Thứ tự triển khai và tiêu chí hoàn thành

| Phase | Hạng mục | Kiểm chứng |
| --- | --- | --- |
| P0 | Đối chiếu nhánh, schema và giao diện giữa các module. Chuẩn bị PostgreSQL cô lập và tách các ca dùng dịch vụ thật khỏi hồi quy mặc định. | Chạy baseline backend/frontend. Xác minh cấu hình cô lập và ghi mốc commit. |
| P1 | Chuẩn hóa OAuth state, danh tính người dùng, phiên đăng nhập, quyền và hành vi refresh phía frontend. | Kiểm thử HTTP, OAuth, refresh và thu hồi quyền. Kiểm tra khởi tạo và nâng cấp schema. |
| P2 | Vòng đời tài khoản, khôi phục, xác minh email, liên kết OAuth, đặt mật khẩu, mời thành viên và ràng buộc người quản lý dự án. | Kiểm thử tài khoản cũ, luồng thư mời, tranh chấp đồng thời và cập nhật quyền sau thay đổi trạng thái. |
| P3 | Kiểm soát quyền trên API, hành động AI có xác nhận và dữ liệu lấy từ bộ nhớ đệm. | Kiểm tra chéo người dùng và dự án. Kiểm tra xác nhận, hủy, hết hạn và thử lại hành động AI. |
| P4 | Quyền tệp và dọn dẹp, thu hồi quyền STOMP/SSE, thông báo đang chờ, lịch và điều hướng cộng tác. | Kiểm tra tải tệp, thu hồi quyền trong khi kết nối đang mở, retry dọn dẹp và luồng lịch. Xác nhận ACL Drive cũ với chủ sở hữu trong môi trường được phép. |
| P5 | Quy tắc sửa task, chốt sprint, nộp bài, đánh giá độc lập, mở lại task và lịch sử chất lượng. | Kiểm thử lifecycle và thao tác đồng thời trên PostgreSQL. Đối chiếu cùng hành vi trên UI, API và hành động AI. |
| P6 | Tính tải công việc, điểm hiệu suất từ đánh giá hợp lệ và xếp hạng ứng viên từ dữ liệu đúng phạm vi. | Kiểm tra xếp hạng tái lập được, trường hợp thiếu dữ liệu và nguồn của từng tiêu chí. |
| P7 | Kết nối phòng họp, âm thanh, đóng phòng, ghi hình qua trình duyệt, lưu tệp và phát lại. | Hồi quy adapter, upload và playback. Nghiệm thu riêng với phòng LiveKit 4–6 người và tệp Drive được phép dùng để test. |
| P8 | Lưu snapshot gợi ý, liên kết lựa chọn của quản lý với thao tác giao task, dựng dữ liệu từ kết quả duyệt và hiển thị báo cáo. | Trên PostgreSQL thật, chạy gợi ý → chọn người → nộp và duyệt task → xem mẫu đúng phạm vi. Kiểm tra mở lại task, idempotency và tách dữ liệu tổng hợp. |
| P9 | Học, đánh giá và áp dụng trọng số Adaptive theo phạm vi. Phụ thuộc P8 và quyết định phương pháp ở [trạng thái bàn giao](STATUS.md). | So với trọng số mặc định trên dữ liệu phát sinh sau mốc học. Kiểm tra đổi phiên bản, khởi động lại, hoàn tác và cô lập phạm vi. |
| P10 | Đối chiếu Analytics với lịch sử thật, kiểm thử chất lượng RAG, bổ sung quan sát hệ thống và đo tải chat/họp. | Kiểm tra số liệu Burndown/Velocity, đánh giá RAG có nhãn, đo tải tin nhắn và phòng họp đa điểm. |
| P11 | Hồi quy cả hai repo, dọn nợ còn lại và chuẩn bị bàn giao bản phát hành. | CI, hợp đồng API, tương thích migration, tài liệu cấu hình, bằng chứng nghiệm thu và kết quả chạy lại kịch bản chính. |

## P9 · Phương án đang chờ chốt

1. **P9.1 · Dùng một phiên bản trọng số cho mỗi lượt gợi ý.** Bộ chấm điểm và bộ mô phỏng dùng cùng cách chuẩn hóa. Phạm vi thí điểm là dự án, quản lý và chế độ `BALANCED`. Dự án hoặc quản lý thiếu dữ liệu dùng trọng số mặc định. Lưu nguồn dữ liệu và phiên bản để truy vết khi điểm duyệt bị hủy.
2. **P9.2 · Đánh giá trọng số trên dữ liệu theo thời gian.** Đề cương ghi Online Gradient Descent. Phương án đang trao đổi là grid search theo đợt, ban đầu thử tỷ lệ kỹ năng/tải công việc và giữ trọng số hiệu suất cố định. Ghi cả thời điểm quyết định và thời điểm duyệt để đặt mốc cắt. Tiêu chí dự kiến là tối thiểu 20 lựa chọn hợp lệ để học và 5 lựa chọn phát sinh sau mốc đó để kiểm tra. Báo điểm thực tế của cả hai bộ trọng số. Dữ liệu tổng hợp dùng để chứng minh cơ chế trong môi trường test.
3. **P9.3 · Quản lý phiên bản và hoàn tác.** Thiết kế đang đề xuất các thao tác đánh giá, duyệt, kích hoạt và hoàn tác. Kiểm tra hai thao tác quản trị đồng thời, giữ phiên bản qua restart, khôi phục thứ hạng sau rollback và quay về bộ mặc định nếu bằng chứng của phiên bản áp dụng bị vô hiệu hóa. Chốt cơ chế cập nhật với giảng viên cùng quyết định phương pháp.

## P10–P11 · Hạng mục tiếp theo

- **Analytics.** Đối chiếu nguồn Burndown/Velocity với thời điểm chốt sprint và thay đổi phạm vi công việc. Dùng lại chức năng sẵn có khi đáp ứng kết quả mong muốn.
- **RAG.** Kiểm tra vòng đời tài liệu và quyền theo dự án. Kế hoạch dự kiến ít nhất 50 câu hỏi có nhãn để đánh giá câu trả lời và tài liệu hỗ trợ, báo tỷ lệ đạt thực tế so với mục tiêu 80%.
- **Quan sát và tải.** Dùng Spring Actuator, Prometheus và Grafana theo phạm vi đề cương. Đo độ trễ và lỗi khi gửi tin nhắn thời gian thực và nối phòng họp 4–6 người. Ghi rõ cấu hình test và giới hạn môi trường.
- **Bàn giao.** Chạy hồi quy backend/frontend, đối chiếu hợp đồng giữa hai repo, kiểm tra Flyway và cập nhật tài liệu cấu hình, giới hạn và kết quả nghiệm thu.

## Kiểm chứng cuối phase

- Backend chạy `mvn verify` với Java 25 và PostgreSQL cô lập theo cấu hình test. Nếu đổi schema, kiểm tra cả khởi tạo DB mới và nâng cấp từ phiên bản trước. Xem [hướng dẫn Flyway](../FLYWAY_TEAM_WORKFLOW.md).
- Frontend chạy `npm test`, `npm run build` và `npx playwright test --project=chromium` khi phase thay đổi luồng giao diện. Kiểm tra lint các file đã sửa.
- Chạy lại các luồng đã hoàn tất bị ảnh hưởng. Ghi phạm vi test, kết quả thực tế, commit của hai repo và phần còn chờ kiểm thử bên ngoài trong [trạng thái bàn giao](STATUS.md).
