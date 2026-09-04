# 🔄 Hugging Face Deployment Verification Workflow

Automated workflow for validating TaskPilot backend prior to deployment.

```mermaid
flowchart TD
    A["Local Code Changes"] --> B["Step 1: Maven Build & Tests\n./mvnw clean test -B"]
    B -->|Pass| C["Step 2: Configuration Audit\nCheck YAML Placeholders & Port 7860"]
    B -->|Fail| F["Fix Compilation / Test Errors"]
    C -->|Pass| D["Step 3: Docker Build Simulation\ndocker build -t taskpilot-hf-test ."]
    C -->|Fail| G["Fix Missing Fallbacks in YAML"]
    D -->|Pass| E["Step 4: Container Smoke Test\nRun as UID 1000 on PORT 7860"]
    D -->|Fail| H["Fix Dockerfile Multi-stage Build"]
    E -->|Pass 15s Survival| I["✅ Safe to Push & Sync to Hugging Face Space"]
    E -->|Exit / Crash| J["Inspect Container Logs & Remediate"]
```

## Running the Automated Pipeline

From project root:
```bash
bash taskpilot/scripts/verify-hf-deploy.sh
```

### Steps Executed:
1. **Maven Clean Package**: Compiles with Java 25 (`--enable-preview`) and runs test suites.
2. **Docker Build**: Builds multi-stage Docker image on `eclipse-temurin:25-jdk-alpine` and `eclipse-temurin:25-jre-alpine`.
3. **Container Smoke Test**:
   - Launches container with `--user 1000:1000` (simulating HF orchestrator).
   - Binds port `7865:7860` with `PORT=7860`.
   - Passes test credentials and checks process survival after 15 seconds.
   - Cleans up container.
