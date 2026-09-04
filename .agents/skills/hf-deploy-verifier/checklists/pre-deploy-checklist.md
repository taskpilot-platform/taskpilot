# ✅ Hugging Face Pre-Deploy Checklist (100% Fail-Safe Gate)

Before pushing any commit to GitHub or deploying to Hugging Face Spaces, verify every item below:

---

### Phase 1: Dockerfile Verification
- [ ] **Java 25 Parity:** Stage 1 uses `eclipse-temurin:25-jdk-alpine` and Stage 2 uses `eclipse-temurin:25-jre-alpine`.
- [ ] **UID 1000 / GID 1000 User:**
  ```dockerfile
  RUN addgroup -g 1000 user && adduser -u 1000 -G user -s /bin/sh -D user
  WORKDIR /home/user/app
  COPY --chown=user:user --from=build /app/taskpilot-app/target/*-SNAPSHOT.jar app.jar
  USER user
  ```
- [ ] **Port Declarations:**
  - `ENV PORT=7860`
  - `ENV SERVER_PORT=7860`
  - `EXPOSE 7860`
- [ ] **JVM Entrypoint Flags:**
  - `--enable-preview`
  - `-XX:MaxRAMPercentage=75.0`
  - `-XX:+ExitOnOutOfMemoryError`
  - `-Djava.net.preferIPv4Stack=true`
  - `-Djava.security.egd=file:/dev/./urandom`

---

### Phase 2: Spring Boot Configuration (`application.yml` & `application-prod.yml`)
- [ ] **Port Resolution:** `server.port: ${PORT:${SERVER_PORT:7860}}` in both default and prod profile.
- [ ] **Host Binding:** `0.0.0.0` (wildcard, never `127.0.0.1`).
- [ ] **Placeholder Safety:** All variables have safe defaults or empty strings:
  - `spring.datasource.url: ${DB_URL:...}`
  - `spring.datasource.username: ${DB_USERNAME:...}`
  - `spring.datasource.password: ${DB_PASSWORD:...}`
  - `application.security.jwt.secret-key: ${JWT_SECRET:...}`
  - `ai.gemini.api-key: ${GEMINI_API_KEY:}`
  - `ai.github.token: ${GITHUB_TOKEN:}`
  - `spring.mail.host: ${MAIL_HOST:}`
- [ ] **Actuator Health Hardening:**
  - `/actuator/health` is permitted in `SecurityConfig.java`.
  - `management.health.mail.enabled: false`.
  - `management.endpoint.health.show-details: when-authorized`.
- [ ] **CORS Settings:**
  - `setAllowedOriginPatterns(List.of("*"))` with `allowCredentials(true)`.
  - Preflight `OPTIONS /**` permitted without authentication.
  - Allowed origins includes `https://*.hf.space` and `https://*.netlify.app`.

---

### Phase 3: Hugging Face Space Settings
- [ ] **`README.md` Frontmatter:**
  ```yaml
  ---
  title: TaskPilot
  sdk: docker
  app_port: 7860
  ---
  ```
- [ ] **Space Secrets Configured (HF Settings -> Secrets):**
  - `DB_URL` (Must include `?sslmode=require` for cloud PostgreSQL)
  - `DB_USERNAME`
  - `DB_PASSWORD`
  - `JWT_SECRET` (256-bit secure secret)
  - `JWT_EXPIRATION` (e.g. 86400000)
  - `GEMINI_API_KEY`
  - `SPRING_PROFILES_ACTIVE=prod`

---

### Phase 4: Local Runtime Simulation Test
- [ ] Execute `bash taskpilot/scripts/verify-hf-deploy.sh`.
- [ ] Output displays `=== Verification PASSED! Safe to push to GitHub & deploy to Hugging Face ===`.
