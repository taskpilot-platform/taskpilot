# Thiết lập Observability Full 3 Trụ Cột (Metrics + Logs + Traces → Grafana Cloud)

Backend TaskPilot hỗ trợ đầy đủ 3 trụ cột Observability miễn phí trên **Grafana Cloud Free Tier** (10.000 active series, 50 GB Logs, 50 GB Traces):

1. **Metrics (Prometheus Agent v3.5.0 → Grafana Cloud Prometheus):**
   - Spring Actuator xuất metrics tại `/actuator/prometheus` (HTTP Basic riêng biệt) kèm custom business metrics (`taskpilot_ai_*`, `taskpilot_egress_*`, `taskpilot_livekit_webhooks_*`, `taskpilot_archive_*`, `taskpilot_websocket_*`).
   - Prometheus Agent chạy cục bộ (`127.0.0.1:9090`) thực hiện `remote_write` mỗi **60 giây**.
   - `micrometer-registry-otlp` đã bị loại bỏ khỏi classpath và `OTEL_METRICS_EXPORTER=none` mặc định để **không bao giờ đẩy trùng metric** lên Grafana Cloud.
2. **Distributed Tracing (Spring Boot 4 OpenTelemetry OTLP → Grafana Cloud Tempo):**
   - Tự động trace HTTP MVC, JPA repositories, `@Scheduled` workers, và custom spans (`taskpilot.ai.chat.stream`, `taskpilot.egress.start`, `taskpilot.egress.webhook`, `taskpilot.archive.transfer`), giữ nguyên `traceId` xuyên qua virtual threads.
   - Tự động lọc bỏ các span thành công của `/actuator/**` (health probe & Prometheus scrape) để tiết kiệm dung lượng Tempo.
3. **Centralized Logs (OpenTelemetry Logback Appender → Grafana Cloud Loki):**
   - Đẩy log `INFO`/`WARN`/`ERROR` kèm `traceId` & `spanId` qua cùng cổng OTLP (nén `gzip`), cho phép bấm từ 1 trace trên **Tempo** nhảy thẳng sang đúng dòng log & stack trace trên **Loki** (và ngược lại).

Nếu thiếu biến môi trường của trụ cột nào, `entrypoint.sh` sẽ tự động bỏ qua trụ cột đó và khởi động ứng dụng Java bình thường.

---

## 1. Biến môi trường cần cấu hình trên Hugging Face Space

Vào **Settings → Variables and secrets** của Space backend (`taskpilot952476/taskpilot`):

### A. Nhóm Metrics (Prometheus Agent → Grafana Cloud Prometheus)

| Tên biến | Loại | Bắt buộc | Mô tả / Ví dụ |
| :--- | :--- | :---: | :--- |
| `PROM_REMOTE_WRITE_URL` | Variable | Có | Remote Write Endpoint (vd: `https://prometheus-prod-XX-prod-ap-southeast-1.grafana.net/api/prom/push`) |
| `PROM_USERNAME` | Variable | Có | Instance ID của Prometheus (chỉ gồm chữ số) |
| `PROM_API_TOKEN` | **Secret** | Có | Grafana Cloud Access Policy Token (`glc_...`) có quyền `metrics:write` |
| `PROMETHEUS_SCRAPE_PASSWORD` | **Secret** | Có | Chuỗi ngẫu nhiên mạnh (32–64 ký tự ASCII; hỗ trợ >72 byte) cho Basic Auth `/actuator/prometheus` |
| `PROMETHEUS_SCRAPE_USERNAME` | Variable | Không | Tên đăng nhập Basic Auth (mặc định: `prometheus`) |
| `APP_ENV` | Variable | Không | Nhãn môi trường `env` / `deployment.environment.name` (mặc định: `prod`) |

> **Lưu ý:** Nếu `PROMETHEUS_SCRAPE_PASSWORD` để trống, `/actuator/prometheus` sẽ bị chặn hoàn toàn với mọi request.

### B. Nhóm Logs (Loki) & Traces (Tempo) qua Grafana Cloud OTLP Gateway

Vào **Grafana Cloud Portal → chọn stack → mục OpenTelemetry → bấm Configure** để lấy thông tin OTLP (tạo token có quyền `traces:write` + `logs:write`).

Bạn có thể cấu hình theo **Cách 1 (khuyên dùng — copy nguyên từ trang OpenTelemetry của Grafana Cloud)** hoặc **Cách 2 (khỏi cần tự mã hóa Base64)**:

| Tên biến | Loại | Bắt buộc | Mô tả / Ví dụ |
| :--- | :--- | :---: | :--- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Variable | Có | OTLP Gateway URL (vd: `https://otlp-gateway-prod-ap-southeast-1.grafana.net/otlp`) |
| `OTEL_EXPORTER_OTLP_HEADERS` | **Secret** | Cách 1 | Chuỗi header copy trực tiếp từ Grafana Cloud (`Authorization=Basic%20<base64>`) |
| `OTEL_INSTANCE_ID` | Variable | Cách 2 | Stack Instance ID (dãy số ở trang OpenTelemetry) — chỉ cần nếu không đặt `OTEL_EXPORTER_OTLP_HEADERS` |
| `OTEL_API_TOKEN` | **Secret** | Cách 2 | Access Policy Token (`glc_...`, quyền `traces:write` + `logs:write`) — `entrypoint.sh` tự tạo header Basic Auth |
| `OTEL_TRACING_SAMPLING_PROBABILITY` | Variable | Không | Tỉ lệ lấy mẫu trace `0.0`–`1.0` (mặc định: `1.0`; nên hạ xuống `0.1`–`0.25` nếu traffic cao) |
| `OTEL_LOG_LEVEL` | Variable | Không | Mức log tối thiểu đẩy sang Loki (mặc định: `INFO`; tránh đặt `DEBUG` để giữ <50 GB/tháng) |

---

## 2. Custom Business Metrics mới (Low-Cardinality)

Tất cả nhãn đều được chuẩn hóa về tập giá trị cố định nhỏ (tổng ~50–65 series mới, tổng toàn app <400 / 10.000 series):

| Metric Prometheus | Nhãn (Labels) | Ý nghĩa |
| :--- | :--- | :--- |
| `taskpilot_ai_requests_total` | `provider` (`gemini`,`groq`,`openrouter`,`other`), `outcome` (`success`,`fallback`,`error`,`aborted`) | Lưu lượng, fallback và lỗi gọi LLM |
| `taskpilot_ai_tokens_total` | `provider` | Tổng lượng token ước tính theo nhà cung cấp AI |
| `taskpilot_ai_request_duration_seconds` | `provider`, `outcome` | Thời gian xử lý request AI (count/sum/max) |
| `taskpilot_egress_recordings_total` | `mode` (`cloud`,`fallback`), `outcome` (`started`,`completed`,`failed`,`backup_storage`,`start_failed`) | Vòng đời ghi hình LiveKit Egress & Tier 3 Fallback |
| `taskpilot_livekit_webhooks_total` | `event` (`egress_started`,`egress_updated`,`egress_ended`,`invalid`,`other`), `outcome` (`accepted`,`rejected`,`error`) | Trạng thái nhận & xác thực webhook LiveKit |
| `taskpilot_archive_transfers_total` | `outcome` (`success`,`failed`,`integrity_mismatch`,`b2_cleanup_failed`) | Số file chuyển từ Backblaze B2 sang Google Drive 5TB |
| `taskpilot_archive_bytes_total` | `outcome="success"` | Tổng dung lượng byte đã archive lên Google Drive |
| `taskpilot_archive_transfer_duration_seconds` | `outcome` (`success`,`failed`) | Thời gian streaming B2 → Drive |
| `taskpilot_websocket_sessions_active` | *(không có)* | Số phiên WebSocket STOMP chat đang kết nối |
| `taskpilot_websocket_messages_total` | `outcome` (`sent`,`auth_denied`) | Số tin nhắn chat STOMP & số lần bị chặn auth |

---

## 3. Kiểm tra trên máy local

```bash
# 1. Không có Basic Auth -> bị chặn
curl -i http://localhost:7860/actuator/prometheus

# 2. Có Basic Auth hợp lệ -> 200 OK + thấy các metric taskpilot_*
curl -u prometheus:local-dev-pass http://localhost:7860/actuator/prometheus | grep -E '^taskpilot_'

# 3. Đếm tổng active series (< 10.000 cho gói Free)
curl -s -u prometheus:local-dev-pass http://localhost:7860/actuator/prometheus | grep -vc '^#'
```

---

## 4. Xác nhận trên Grafana Cloud

1. Đợi ~1–2 phút sau khi Space restart và log hiện:
   - `[monitoring] prometheus agent started`
   - `[monitoring] OTLP traces+logs enabled (endpoint=...)`
   - `[monitoring] OpenTelemetry Logback appender installed`
2. **Prometheus (Metrics):** Mở **Explore → `grafanacloud-<stack>-prom`**:
   - `up{app="taskpilot"}` → `1`
   - `count({application="taskpilot"})` → kiểm tra tổng active series < 10.000
3. **Loki (Logs):** Mở **Explore → `grafanacloud-<stack>-logs`** → chạy `{service_name="taskpilot"}` (hoặc lọc lỗi `{service_name="taskpilot", severity_text="ERROR"}`).
4. **Tempo (Traces):** Mở **Explore → `grafanacloud-<stack>-traces`** → chạy `{resource.service.name="taskpilot"}` → mở 1 trace bất kỳ (vd `POST /api/v1/ai/chat/stream`) để xem waterfall từng span và bấm **Logs for this span** để nhảy sang Loki.
