# TaskPilot RAG Verification Protocol Reference

## Rules
- **Rule 1**: Every verification claim must be backed by an executed command and logged in `docs/implementation/rag/VERIFICATION.md`.
- **Rule 2**: Run module-specific tests first, followed by end-to-end integration and security isolation tests, and finally a full regression run.

## Standard Verification Commands
```powershell
# 1. Quick compilation check across all modules
.\mvnw.cmd test-compile

# 2. Run AI module tests
.\mvnw.cmd test -pl taskpilot-ai

# 3. Run Projects module tests
.\mvnw.cmd test -pl taskpilot-projects

# 4. Run RAG specific tests
.\mvnw.cmd test -pl taskpilot-ai -Dtest=*Rag*,*Document*,*Knowledge*

# 5. Full workspace regression test
.\mvnw.cmd test
```

## Failure Triage Procedure
- If tests fail with preview language warnings: Ensure `--enable-preview` and `-Dnet.bytebuddy.experimental=true` are present.
- If Flyway migration fails: Verify PostgreSQL connection and pgvector extension availability.
- If vector dimension mismatch occurs: Verify embedding output matches table definition (`vector(768)`).
