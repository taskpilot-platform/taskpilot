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
| **Đợt 02** | 09/09/2026 – 22/09/2026 | **P1: Phân hệ RAG - Tri thức dự án & Điều phối bất đồng bộ** | - Tích hợp `pgvector`, HNSW index & mô hình canonical `gemini-embedding-2` 768 chiều.<br>- Xây dựng pipeline trích xuất văn bản Apache Tika (PDF/DOCX/MD/CSV) & recursive chunking.<br>- Hàng đợi bền vững PostgreSQL (`document_chunk_staging`, V27/V30) hỗ trợ nạp tri thức khôi phục (Resumable Ingestion).<br>- Bộ điều tiết hạn ngạch 2 chiều (100 RPM, 30k TPM) với Normal Pacing & bảo vệ Headroom chat.<br>- Thực nghiệm khoa học đo lường Quota Google Gemini: Khẳng định cơ chế tính theo từng input text (20 texts = 20 quota units), tích hợp cơ chế xoay vòng đa khóa (`GEMINI_API_KEYS`).<br>- Phân quyền RBAC cho Kho tri thức: Manager toàn quyền upload/retry/xóa, Member chỉ đọc & tìm kiếm.<br>- Tích hợp LangChain4j RAG tool (`searchProjectKnowledge`) vào AI Copilot & giao diện Knowledge tab. | - BE: 121/121 tests pass<br>- FE: 23/23 tests pass<br>- Stress test: DOCX 212 chunks pass<br>- Thực nghiệm Quota: 100% verified<br>- UAT & RBAC: 100% verified | [Xem chi tiết](logs/period-2026-w37-w38.md) | ✅ Hoàn thành |
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
