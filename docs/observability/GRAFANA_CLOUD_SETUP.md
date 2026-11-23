# Thiết lập Observability (Prometheus Agent → Grafana Cloud)

Backend TaskPilot xuất metrics định dạng Prometheus tại `/actuator/prometheus` (bảo vệ bằng HTTP Basic riêng biệt) và chạy kèm **Prometheus v3.5.0 ở chế độ agent** trong cùng container Hugging Face Space để `remote_write` sang Grafana Cloud mỗi **60 giây** (`127.0.0.1:9090`, không mở cổng ra ngoài).

Nếu thiếu bất kỳ biến môi trường bắt buộc nào bên dưới, `entrypoint.sh` sẽ tự động bỏ qua Prometheus agent và khởi động ứng dụng Java bình thường.

## 1. Biến môi trường cần cấu hình trên Hugging Face Space

Vào **Settings → Variables and secrets** của Space backend (`taskpilot952476/taskpilot`):

| Tên biến | Loại | Bắt buộc | Mô tả / Ví dụ |
| :--- | :--- | :---: | :--- |
| `PROM_REMOTE_WRITE_URL` | Variable | Có | Remote Write Endpoint từ Grafana Cloud (vd: `https://prometheus-prod-XX-prod-ap-southeast-1.grafana.net/api/prom/push`) |
| `PROM_USERNAME` | Variable | Có | Username / Instance ID của Prometheus trên Grafana Cloud (chỉ gồm chữ số) |
| `PROM_API_TOKEN` | **Secret** | Có | Grafana Cloud Access Policy Token (`glc_...`) chỉ cần quyền `metrics:write` |
| `PROMETHEUS_SCRAPE_PASSWORD` | **Secret** | Có | Chuỗi ngẫu nhiên bất kỳ (khuyến nghị 32–64 ký tự ASCII; hỗ trợ an toàn cả mật khẩu > 72 byte) dùng cho Basic Auth của `/actuator/prometheus` |
| `PROMETHEUS_SCRAPE_USERNAME` | Variable | Không | Tên đăng nhập Basic Auth (mặc định: `prometheus`) |
| `APP_ENV` | Variable | Không | Nhãn môi trường `env` gửi kèm metric (mặc định: `prod`) |

> **Lưu ý:** Nếu `PROMETHEUS_SCRAPE_PASSWORD` để trống, endpoint `/actuator/prometheus` sẽ bị chặn hoàn toàn (`403 Forbidden`) với mọi request.

## 2. Kiểm tra trên máy local

1. Đặt `PROMETHEUS_SCRAPE_PASSWORD` (ví dụ `local-dev-pass`) rồi khởi động backend.
2. Kiểm tra bảo mật & dữ liệu scrape:

```bash
# Không có thông tin đăng nhập -> 401 Unauthorized
curl -i http://localhost:7860/actuator/prometheus

# Có Basic Auth hợp lệ -> 200 OK
curl -u prometheus:local-dev-pass http://localhost:7860/actuator/prometheus | head -n 40

# Đếm tổng số active series xuất ra (nên < 10.000 cho gói free)
curl -s -u prometheus:local-dev-pass http://localhost:7860/actuator/prometheus | grep -vc '^#'
```

## 3. Xác nhận trên Grafana Cloud

1. Đợi khoảng 1–2 phút sau khi Space backend khởi động xong và log hiện `[monitoring] prometheus agent started`.
2. Mở Grafana Cloud → **Explore**, chọn datasource Prometheus (`grafanacloud-<stack>-prom`) và chạy truy vấn:
   - `up{app="taskpilot"}` → kết quả bằng `1` là thành công.
   - `jvm_memory_used_bytes{application="taskpilot"}`
   - `http_server_requests_seconds_count{application="taskpilot"}`

## 4. Import Dashboard khuyến nghị

Vào **Dashboards → New → Import** và nhập các ID chính thức:

- **`4701`** — *JVM (Micrometer)* (Heap, GC pause, threads, CPU)
- **`19004`** — *Spring Boot 3.x Statistics* (HTTP RPS, latency buckets, HikariCP, uptime)

## 5. Theo dõi hạn mức gói Free (Grafana Cloud)

- Gói miễn phí của Grafana Cloud giới hạn **10.000 active series** (tính theo chu kỳ 1 mẫu/phút = 1 DPM, đã cấu hình `scrape_interval: 60s` và 7 mốc SLO cố định thay vì `percentiles-histogram`).
- Định kỳ kiểm tra tại **Billing → Current usage** để đảm bảo số lượng **active series < 10k**.
