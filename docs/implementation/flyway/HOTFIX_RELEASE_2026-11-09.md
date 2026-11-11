# Application recovery hotfix — 09/11/2026

> **Bản ghi lịch sử 09/11:** các kết luận dưới đây mô tả thời điểm điều tra/hotfix ban đầu. Ngày 10/11, nguồn đã tiếp nhận Flyway V42, validate đạt và app mới đã deploy/live smoke thành công; xem [kết quả phát hành cuối](RECOVERY_RELEASE_2026-11-10.md). Dùng [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md) cho migration mới, không lặp lại hướng recovery thử nghiệm cũ.

## Release scope

The application hotfix fixes false HTTP success for JDBC failures, returns safe JSON 409 responses for integrity conflicts, normalizes email consistently across registration/admin/OAuth/password-reset throttling, and fetches the refresh-token user before its persistence context closes.

The refresh failure was reproduced as HTTP 500 with `LazyInitializationException` in a production-profile Docker smoke test. A PostgreSQL 17 regression test failed before the repository fetch-graph change and passed afterwards.

This release contains no SQL migration, migration-history edit, default Flyway configuration change, recovery profile activation, or bulk account-data modification. The separate recovery-profile/bundle work remains outside this hotfix commit.

## Local verification

- Working-tree reactor: 375 tests, zero failures/errors/skips; excludes four opt-in live-service classes. Two of these tests belong to the separate, unreleased recovery profile. Release CI results must be recorded separately.
- Python recovery tooling: 7/7 passed; tooling is separate from this application release.
- Actual Dockerfile built successfully with Java 25 Alpine, UID/GID 1000, port 7860 and profile `prod`.
- Smoke environment: synthetic data, PostgreSQL 17, internal Docker network, no source credentials or `.env` mounted. Requests run over loopback inside the container.
- HTTP checks passed: registration 201; login/profile/refresh 200; duplicate registration 409 with JSON; admin creation 201 with canonical stored email; reset/login 200; reset invalidates old refresh token; concurrent registration yields 201/409 with exactly one stored row; project/task creation and task reads pass; meeting schedule/create/read pass.
- Legacy history digest unchanged; no `flyway_schema_history_v2` table created by the production-profile hotfix startup.
- Smoke container is deliberately stopped after successful verification; its exit 143 is not an application test failure.
- The deployment skill's referenced `scripts/verify-hf-deploy.sh` is absent in this checkout. Its build/configuration/container gates were executed directly, including real HTTP behavior rather than process survival alone.

## Backup scope

A restricted-access backup of the configured source's `public` structure and application data was created and successfully restored into a separate PostgreSQL 17 container with `--network none`. Restore completed without errors and aggregate verification matched the source snapshot. The verification container was removed afterwards; the backup remains outside Git.

This is not a complete Supabase/PITR backup: external schemas, original ownership and ACL are outside its scope. It does not authorize a Flyway cutover or reconstruction of historical account emails.

## Deployment status

Released through [PR #51](https://github.com/taskpilot-platform/taskpilot/pull/51), hotfix commit `f55a7033a222d4ab07756dcc0ff68706ffb7b7a8`, merge commit `d5427747e7b796e1c164505225b73bd393a36ecb`.

- PR CI: 380 tests, zero failures/errors, six live-service tests skipped. Maven and Docker build passed; CodeQL passed.
- [Main deployment workflow](https://github.com/taskpilot-platform/taskpilot/actions/runs/42650988190): build/test and sync succeeded.
- Hugging Face revision `7575d76800de460482d777e09a385237efbf5fde` reached `RUNNING`; all ten changed production source files matched the hotfix commit.
- Live smoke at 23:10 +07:00: register 201; login/profile/refresh 200; duplicate email 409 with JSON; health UP. The generated canonical test account was observed in the configured source, confirming that the live application uses that database.
- Legacy migration history digest matched the pre-release backup before the smoke and remained unchanged afterwards.
- Only the synthetic smoke account and its refresh-token rows were deleted during cleanup. User count was 458 before and 458 after the smoke. No existing account email, SQL migration, Flyway history or RLS configuration was modified.

The separate Flyway/RLS/account-reconciliation work remains open. Keep the migration recovery work separate; do not enable `recovery` to deploy this hotfix.
