# Flyway Recovery and Full Application Integration Plan

> **For agentic workers:** Use superpowers:executing-plans. Execute the existing recovery runbook approved in this conversation; keep the user informed without repeating design/permission gates.

**Goal:** Restore an enforceable Flyway chain on the configured source and verify the full running application against the reconciled schema.
**Architecture:** Reviewed public-schema snapshot at V41, forward-only V42 reconciliation, separate `flyway_schema_history_v2`; preserve legacy history and all existing application rows. Existing DB adoption is a deliberate operator action after preflight/backup, never automatic startup baseline.
**Tech Stack:** Java 25, Spring Boot 4.1, Flyway 11.3.2, PostgreSQL 17, Docker, React/Vite/Playwright.
**Spec:** `docs/implementation/flyway/RECOVERY_RUNBOOK.md`, section B, and the user's explicit direction to complete Flyway and full integration testing.

## Global Constraints
- No repair, clean, historical migration edit, replay of email normalization, bulk user rewrite, or changes to RAG internals.
- Keep source legacy history immutable. Protect the new history from client roles.
- Backups/data/credentials stay outside Git. No real credentials in synthetic application tests.
- Reuse the current recovery branch/check-out; preserve unrelated dirty progress files.

## Review Focus
- Wrong database/schema or history must be rejected before adoption.
- Same-name index or existing schedule columns with wrong definitions must fail.
- Fresh snapshot must include runtime-required settings; existing settings must remain intact.
- Source role/ACL and dependencies outside public must be reviewed before source adoption.
- A green mock UI suite is not real backend/browser integration.

### Task 1: Versioned reconciled schema and guards
**Files:** `taskpilot-app/src/main/resources/db/reconciled/V41__reviewed_schema_baseline.sql`, `V42__reconcile_meeting_schedule.sql`; `taskpilot-app/src/test/java/com/taskpilot/app/migration/ReconciledFlywayIntegrationTest.java`.
**Interfaces:** Produces `classpath:db/reconciled`, versions 41/42, history table `flyway_schema_history_v2` for later tasks.
- [ ] Write/run PostgreSQL tests: fresh migration, explicit existing adoption, restart, history/data preservation, wrong type/index refusal, protected metadata.
- [ ] Observe missing bundle failure before adding reviewed schema SQL and forward-only guarded migration.
- [ ] Verify focused tests pass; commit only reviewed migrations/tests.
Expected: fresh executes 2; adopted executes 1 after explicit baseline; restart 0; validation true; negative cases fail without rewriting data.

### Task 2: Guarded operator adoption and default configuration
**Files:** `tools/flyway-recovery/AdoptHistory.java`, `taskpilot-app/src/main/resources/application.yml`, profile safety tests.
**Interfaces:** Consumes Task 1 bundle; operator requires exact DB/user/URL digest, schema fingerprint and legacy-history digest. Produces v2 history at 42 with successful validation.
- [ ] Write/run tests asserting validation true, automatic baseline false, clean disabled and reconciled location/history. Observe failure on old defaults.
- [ ] Implement minimal operator preflight/adoption and fail-closed defaults. Retain old SQL files as historical evidence, outside active location.
- [ ] On isolated restored DB, prove wrong target/fingerprint refuses before history creation; approved adoption preserves all data/history.
Expected: no implicit adoption; current source-compatible schema accepted; wrong targets rejected.

### Task 3: Full app integration and source rollout
**Files:** durable integration evidence/runbook updates; tests only where verified failures require fixes.
**Interfaces:** Consumes reviewed migrations/operator and a restored backup. Produces actual backend+browser checks and source Flyway validate result.
- [ ] Review source roles/ACL/extensions/public dependencies; take fresh restricted backup and prove restore.
- [ ] Run whole backend with fresh and restored schemas, HTTP workflows and real browser/backend checks; run frontend build/tests and backend regression. Separate external-provider results explicitly.
- [ ] Obtain fresh whole-change review; resolve important findings with failing-then-passing tests.
- [ ] Verify remote main unchanged, explicitly adopt source using exact preflight evidence, validate/migrate and verify history/data digests.
- [ ] Publish reviewed application config/migrations through CI and verify restart/source validation/live workflows after rollout.
Expected: source v2 history validates at 42; old history and existing data preserved; deployed default startup uses validation; application and integration results have recorded commands and outcomes.
