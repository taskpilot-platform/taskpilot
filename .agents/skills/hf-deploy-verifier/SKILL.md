---
name: hf-deploy-verifier
description: Verify Maven build, Java 25 compatibility, Docker container execution, and Hugging Face deployment safety prior to pushing code to GitHub.
---

# Hugging Face Deployment & Pre-Push Verifier Skill

This skill guarantees that Java 25 / Spring Boot backend changes are 100% fail-safe against Hugging Face Spaces (Docker Space) runtime constraints before code is merged or pushed to GitHub.

---

## 🎯 Core Capabilities
1. **Failure Mode Audit**: Detects and eliminates the 14 classic failure modes where local execution succeeds but Hugging Face deployment fails (UID 1000 mismatch, port 7860 routing, unresolved `${VAR}` placeholders, cloud DB SSL, Actuator mail probes).
2. **Runtime Environment Parity**: Enforces Java 25 preview flags, cgroups v2 memory allocation, and Alpine `musl` DNS IPv4 priority.
3. **Automated Verification Pipeline**: Simulates the exact Hugging Face container execution context locally using `taskpilot/scripts/verify-hf-deploy.sh`.

---

## 📋 Verification Protocol for AI Agents

Whenever modifying backend code, configuration, or Dockerfiles:

### 1. Consult Documentation & Checklist
- Review the [Failure Modes Catalog](references/failure-modes-catalog.md) to anticipate environment discrepancies.
- Complete the [Pre-Deploy Checklist](checklists/pre-deploy-checklist.md).
- Follow the [Deployment Verification Workflow](workflows/deploy-verification-workflow.md).

### 2. Execute Automated Verification
Run the verification script before pushing:
```bash
bash taskpilot/scripts/verify-hf-deploy.sh
```

### 3. Verify Output Gate
Ensure the terminal displays:
```text
=== Verification PASSED! Safe to push to GitHub & deploy to Hugging Face ===
```
If non-zero exit code occurs, inspect the container logs immediately and resolve the root cause using the Failure Modes Catalog.
