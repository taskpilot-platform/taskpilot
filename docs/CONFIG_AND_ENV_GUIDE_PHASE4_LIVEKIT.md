# HƯỚNG DẪN CẤU HÌNH BIẾN MÔI TRƯỜNG & CHUẨN BỊ GIAI ĐOẠN 4 (LIVEKIT VIDEO MEETING)
**Dự án**: TaskPilot - Hệ thống Quản lý Dự án Thông minh tích hợp AI Agent  
**Tài liệu tham chiếu**: Đề cương Đồ án 2 & Hướng dẫn Vận hành Hệ thống  
**Ngày cập nhật**: 08/10/2026  

---

## 1. GIẢI THÍCH SỰ CỐ REDIS NXDOMAIN VÀ CƠ CHẾ SỬA LỖI

### 1.1. Triệu chứng sự cố (Log trên Hugging Face Spaces)
```text
WARN 1 --- [taskpilot-app] [oundedElastic-1] b.d.r.h.DataRedisReactiveHealthIndicator : Redis health check failed
org.springframework.data.redis.RedisConnectionFailureException: Unable to connect to Redis
Caused by: io.lettuce.core.RedisConnectionException: Unable to connect to easy-vervet-91102.upstash.io/<unresolved>:6379
Caused by: io.netty.resolver.dns.DnsResolveContext$SearchDomainUnknownHostException: Failed to resolve 'easy-vervet-91102.upstash.io' [A(1)]
Caused by: io.netty.resolver.dns.DnsErrorCauseException: Query failed with NXDOMAIN
```

### 1.2. Nguyên nhân cốt lõi (Root Cause)
1. **Lỗi DNS NXDOMAIN (Non-Existent Domain)**: Tên miền `easy-vervet-91102.upstash.io` trên nhà cung cấp Upstash Redis đã bị xóa, hết hạn hoặc thay đổi định danh cơ sở dữ liệu. Do đó, hệ thống DNS toàn cầu phản hồi mã `NXDOMAIN` (tên miền không tồn tại).
2. **Cơ chế Health Check của Spring Boot Actuator**: Mặc dù dự án đã cấu hình `JWT_BLOCKLIST_PROVIDER=memory` (sử dụng ConcurrentHashMap trong RAM để lưu trữ token thu hồi, không phụ thuộc Redis), nhưng do dependency `spring-boot-starter-data-redis` có mặt trong classpath, Spring Boot Actuator tự động kích hoạt `DataRedisReactiveHealthIndicator` để kiểm tra kết nối Redis định kỳ. Khi kết nối thất bại, Actuator sẽ ghi nhận cảnh báo và có thể đánh dấu container là `UNHEALTHY` (DOWN).

### 1.3. Giải pháp đã thực hiện (Miễn nhiễm sự cố)
Trong cả `application.yml` và `application-prod.yml`, hệ thống đã cấu hình tắt kiểm tra sức khỏe Redis của Actuator:
```yaml
management:
  health:
    mail:
      enabled: false
    redis:
      enabled: false
```
Nhờ đó, Spring Boot khởi động mượt mà, Tomcat chạy ổn định trên cổng 7860 mà không bị gián đoạn hay crash container ngay cả khi Redis không khả dụng.

---

## 2. HƯỚNG DẪN CẤU HÌNH CHO GIAI ĐOẠN 4: LIVEKIT VIDEO MEETING

Giai đoạn 4 theo đề cương (21/10/2026 – 03/11/2026) bao gồm:
* Khởi tạo phòng họp video/audio trực tuyến thời gian thực giữa các thành viên dự án.
* Chia sẻ màn hình (Screen sharing), điều khiển mic/camera.
* Ghi hình cuộc họp (Recording qua LiveKit Egress) và tự động lưu trữ vào kho tệp dự án (Project File Storage).

### 2.1. Đăng ký tài khoản LiveKit Cloud (Miễn phí)
1. Truy cập [cloud.livekit.io](https://cloud.livekit.io/) và đăng nhập bằng tài khoản Google hoặc GitHub.
2. Tạo một Project mới (ví dụ đặt tên: `taskpilot-collab`).
3. Sau khi tạo project, điều hướng vào mục **Project Settings** > **Keys**:
   * **WebSocket URL**: Dạng `wss://taskpilot-collab-xxxxxx.livekit.cloud`
   * **API Key**: Dạng `APIxxxxxxxxxxxx`
   * **API Secret**: Dạng `secretxxxxxxxxxxxxxxxxxxxx`
4. Sao chép 3 giá trị trên để chuẩn bị thêm vào `.env`.

### 2.2. Các biến môi trường cần bổ sung cho Giai đoạn 4
Trong file `.env` (cả local và cấu hình Secrets trên Hugging Face Spaces / Production), bổ sung nhóm biến:
```env
# ==============================================================================
# GIAI ĐOẠN 4: LIVEKIT VIDEO MEETING & RECORDING
# ==============================================================================
LIVEKIT_URL=wss://taskpilot-collab-xxxxxx.livekit.cloud
LIVEKIT_API_KEY=APIxxxxxxxxxxxxxxxxxxxx
LIVEKIT_API_SECRET=secretxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
LIVEKIT_EMPTY_TIMEOUT_SECONDS=300
LIVEKIT_MAX_PARTICIPANTS=15
```

### 2.3. Tích hợp Egress Recording lưu vào S3 (Tùy chọn)
Nếu nhóm triển khai tính năng tự động ghi hình cuộc họp, LiveKit Cloud hỗ trợ xuất video trực tiếp vào S3 Storage. Bạn có thể sử dụng trực tiếp Supabase S3 bucket `documents` đã có sẵn:
* **Storage Type**: S3 Compatible
* **Endpoint**: Giá trị của `SUPABASE_S3_ENDPOINT`
* **Access Key**: Giá trị của `SUPABASE_S3_ACCESS_KEY`
* **Secret Key**: Giá trị của `SUPABASE_S3_SECRET_KEY`
* **Bucket**: `documents`
* **Region**: `ap-southeast-1`

---

## 3. FILE MẪU `.env` TOÀN DIỆN CHO DỰ ÁN TASKPILOT (FULL TEMPLATE)

Dưới đây là mẫu file `.env` hoàn chỉnh chứa 100% các biến môi trường của tất cả các phân hệ:

```env
# ==============================================================================
# 1. CƠ SỞ DỮ LIỆU POSTGRESQL (SUPABASE PGVECTOR)
# ==============================================================================
# Lưu ý: Cổng 6543 là Transaction Pooler của Supabase, cần prepareThreshold=0
DB_URL=jdbc:postgresql://aws-1-ap-southeast-1.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0
DB_USERNAME=postgres.vbuvggybjjnotyxtbtnw
DB_PASSWORD=YOUR_SUPABASE_DB_PASSWORD

# ==============================================================================
# 2. XÁC THỰC BẢO MẬT (JWT & AUTH)
# ==============================================================================
JWT_SECRET=d6f0f991aa2c94368d21776821a88fa305402cbf2cbcecbca0b5dee2f68094e4
JWT_EXPIRATION=86400000
JWT_BLOCKLIST_PROVIDER=memory
PASSWORD_RESET_EXPIRATION=900000

# ==============================================================================
# 3. DỊCH VỤ EMAIL (BREVO SMTP RELAY)
# ==============================================================================
MAIL_HOST=smtp-relay.brevo.com
MAIL_PORT=2525
MAIL_USERNAME=YOUR_BREVO_SMTP_USERNAME
MAIL_PASSWORD=YOUR_BREVO_SMTP_KEY
MAIL_FROM="TaskPilot Support<support.taskpilot@gmail.com>"

# ==============================================================================
# 4. BỘ NHỚ ĐỆM & RATE LIMIT (UPSTASH REDIS)
# ==============================================================================
# Nếu sử dụng Upstash Redis, tạo database mới tại https://console.upstash.com/redis
REDIS_HOST=YOUR_UPSTASH_REDIS_ENDPOINT.upstash.io
REDIS_PASSWORD=YOUR_UPSTASH_REDIS_PASSWORD
REDIS_PORT=6379
REDIS_SSL=true

# ==============================================================================
# 5. KHO LƯU TRỮ TỆP TIN & AVATAR (SUPABASE S3 COMPATIBLE STORAGE)
# ==============================================================================
SUPABASE_S3_ENDPOINT=https://vbuvggybjjnotyxtbtnw.storage.supabase.co/storage/v1/s3
SUPABASE_S3_ACCESS_KEY=YOUR_SUPABASE_S3_ACCESS_KEY
SUPABASE_S3_SECRET_KEY=YOUR_SUPABASE_S3_SECRET_KEY
SUPABASE_S3_REGION=ap-southeast-1
SUPABASE_S3_BUCKET=avatars
SUPABASE_S3_DOCUMENT_BUCKET=documents
SUPABASE_S3_PUBLIC_URL=https://vbuvggybjjnotyxtbtnw.supabase.co/storage/v1/object/public/avatars

# ==============================================================================
# 6. THÔNG BÁO THỜI GIAN THỰC (ONESIGNAL PUSH NOTIFICATIONS)
# ==============================================================================
ONESIGNAL_APP_ID=YOUR_ONESIGNAL_APP_ID
ONESIGNAL_REST_API_KEY=YOUR_ONESIGNAL_REST_API_KEY

# ==============================================================================
# 7. AI LLM GATEWAY (MULTI-KEY ROTATION & FAILOVER)
# ==============================================================================
# Google Gemini (Dùng cho Chat AI, Embedding 768-dim RAG)
GEMINI_API_KEY=YOUR_PRIMARY_GEMINI_API_KEY
GEMINI_API_KEYS=KEY_1,KEY_2

# Groq Fast Inference (Llama 3.3, Llama 4 Scout)
GROQ_API_KEY=YOUR_PRIMARY_GROQ_KEY
GROQ_API_KEYS=KEY_1,KEY_2,KEY_3,KEY_4,KEY_5
AI_GROQ_ENABLED=true
AI_GROQ_GATEKEEPER_MODEL=llama-3.1-8b-instant

# OpenRouter Fallback Models (Free models)
OPENROUTER_API_KEY=YOUR_PRIMARY_OPENROUTER_KEY
OPENROUTER_API_KEYS=KEY_1,KEY_2,KEY_3,KEY_4
AI_OPENROUTER_ENABLED=true

# GitHub Models Token
GITHUB_TOKEN=YOUR_GITHUB_PAT_TOKEN

# ==============================================================================
# 8. MÔI TRƯỜNG WEB & CORS
# ==============================================================================
FE_ORIGIN="https://taskpilot-platform.netlify.app, http://localhost:5173, http://127.0.0.1:5173"

# ==============================================================================
# 9. GIAI ĐOẠN 4: LIVEKIT VIDEO MEETING (CHUẨN BỊ CHO KỲ 21/10)
# ==============================================================================
LIVEKIT_URL=wss://YOUR_PROJECT.livekit.cloud
LIVEKIT_API_KEY=YOUR_LIVEKIT_API_KEY
LIVEKIT_API_SECRET=YOUR_LIVEKIT_API_SECRET
```

---

## 4. BIẾN MÔI TRƯỜNG CHO FRONTEND (`taskpilot-frontend/.env.local`)

Đối với giao diện Frontend:
```env
# Backend API Base URL
VITE_API_BASE_URL=http://localhost:8080/api

# Vite proxy target cho local development
VITE_API_PROXY_TARGET=http://localhost:8080

# Push Notification
VITE_ONESIGNAL_APP_ID=YOUR_ONESIGNAL_APP_ID

# Tùy chọn: URL WebSocket riêng nếu không dùng tự động phân giải
# VITE_WS_URL=ws://localhost:8080/ws/chat

# Chuẩn bị cho Giai đoạn 4: LiveKit Server URL cho frontend WebRTC
# VITE_LIVEKIT_URL=wss://YOUR_PROJECT.livekit.cloud
```
