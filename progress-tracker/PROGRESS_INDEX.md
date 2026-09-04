# BẢNG TỔNG QUAN TIẾN ĐỘ THỰC HIỆN ĐỒ ÁN 2 (TASKPILOT)
**Học phần**: SE121 - Đồ án 2 | Trường Đại học Công nghệ Thông tin (UIT)  
**Thời gian thực hiện**: 01/09/2026 – 01/01/2027  
**Sinh viên**: Phan Lê Minh (23520952), Đặng Phú Thiện (23521476)  
**Giảng viên hướng dẫn**: ThS. Trần Thị Hồng Yến  

---

## 📊 Bảng Theo Dõi Tiến Độ Định Kỳ 2 Tuần (Bi-Weekly Progress Master Index)

| Đợt | Khoảng thời gian | Giai đoạn đề cương | Các kết quả & Milestone chính đạt được | Định lượng kết quả | Báo cáo chi tiết | Trạng thái |
|:---:|:---:|:---:|---|---|:---:|:---:|
| **Đợt 01** | 25/08/2026 – 08/09/2026 | **P0: Tái cấu trúc kiến trúc & Môi trường Cloud** | - Refactor toàn diện Backend & Frontend (-1485 LOC boilerplate).<br>- Khắc phục 14 failure modes khi deploy lên Hugging Face Spaces (UID 1000, port 7860, Actuator probe fix, musl IPv4).<br>- Thiết lập bộ kỹ năng Agent tự động hóa (`code-refactor`, `hf-deploy-verifier`, `biweekly-progress-tracker`). | - BE: -397 LOC<br>- FE: -1088 LOC<br>- 100% test & build pass<br>- HF Docker verification: PASS | [Xem chi tiết](logs/period-2026-w35-w36.md) | ✅ Hoàn thành |
| **Đợt 02** | 09/09/2026 – 22/09/2026 | **P1: Khởi động RAG - Document Ingestion** | - Tích hợp `pgvector` vào PostgreSQL.<br>- Cài đặt module phân tách văn bản (Apache Tika & Chunking Service).<br>- API upload tài liệu theo project/workspace. | *Dự kiến: Đạt 100% upload & chunking tài liệu PDF/Word* | *Đang tiến hành* | ⏳ Kế hoạch |
| **Đợt 03** | 23/09/2026 – 06/10/2026 | **P1: Embedding & Semantic Search Pipeline** | - Tích hợp embedding model & lưu trữ vector database.<br>- Xây dựng Semantic Search engine và Retrieval chain với LangChain4j.<br>- Chatbot trả lời dựa trên context tài liệu. | *Dự kiến: Đạt độ chính xác semantic retrieval > 85%* | *Chưa bắt đầu* | ⏳ Kế hoạch |
| **Đợt 04** | 07/10/2026 – 20/10/2026 | **P2: Khởi tạo Teams-clone - File Storage & Chat Room** | - Module lưu trữ tài liệu dự án (File upload, download, metadata).<br>- Chat room trao đổi thời gian thực giữa các thành viên. | *Dự kiến: Độ trễ tin nhắn WebSocket < 100ms* | *Chưa bắt đầu* | ⏳ Kế hoạch |
| **Đợt 05** | 21/10/2026 – 03/11/2026 | **P2: Video Meeting & Recording (LiveKit)** | - Tích hợp LiveKit Server cho cuộc họp trực tuyến video/audio.<br>- Tự động ghi hình (recording) và lưu vào kho lưu trữ dự án. | *Dự kiến: Hỗ trợ họp đồng thời 8 thành viên mượt mà* | *Chưa bắt đầu* | ⏳ Kế hoạch |
| **Đợt 06** | 04/11/2026 – 17/11/2026 | **Observability & Identity Provider (IAM)** | - Triển khai Prometheus & Grafana giám sát metrics trên HF Space riêng.<br>- Tích hợp Authentik IAM hỗ trợ Social Login (Google/GitHub) & SSO. | *Dự kiến: Dashboard giám sát thời gian thực 100%* | *Chưa bắt đầu* | ⏳ Kế hoạch |
| **Đợt 07** | 18/11/2026 – 01/12/2026 | **Adaptive Weights & Dashboard phân tích** | - Hiện thực thuật toán học trọng số thích ứng (Online Gradient Descent).<br>- Dashboard phân tích thống kê tiến độ, velocity, workload dự án. | *Dự kiến: Thuật toán tự học cải thiện 25% độ hài lòng PM* | *Chưa bắt đầu* | ⏳ Kế hoạch |
| **Đợt 08** | 02/12/2026 – 15/12/2026 | **Đánh giá hiệu năng, Viết tài liệu báo cáo & Nghiệm thu** | - Hoàn thiện tài liệu báo cáo Đồ án 2 theo mẫu UIT.<br>- Benchmark hiệu năng hệ thống, kiểm thử tải toàn diện.<br>- Chuẩn bị slide thuyết trình và video demo sản phẩm. | *Dự kiến: Báo cáo hoàn chỉnh > 100 trang, demo 100% kịch bản* | *Chưa bắt đầu* | ⏳ Kế hoạch |

---

## 🔗 Liên Kết Kho Mã & Báo Cáo
- **Backend Service**: [taskpilot-platform/taskpilot](https://github.com/taskpilot-platform/taskpilot)
- **Frontend App**: [taskpilot-platform/taskpilot-frontend](https://github.com/taskpilot-platform/taskpilot-frontend)
- **Hệ Thống Báo Cáo (Typst/Latex)**: [taskpilot-platform/report](https://github.com/taskpilot-platform/report)
- **Landing Page**: [taskpilot-platform.github.io](https://taskpilot-platform.github.io)
