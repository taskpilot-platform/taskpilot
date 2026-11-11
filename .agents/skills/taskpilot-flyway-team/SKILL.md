---
name: taskpilot-flyway-team
description: Use when adding or reviewing TaskPilot schema/entity changes, choosing a Flyway version, resolving migration collisions, or starting/deploying a branch against the team's shared database.
---

# TaskPilot shared Flyway workflow

This skill is standalone. It requires no Superpowers plugin or other skill. Run commands from the backend repository root; humans use the same [team guide](../../../docs/development/FLYWAY_TEAM_WORKFLOW.md).

## Check before assigning a version

```sh
python -m pip install -r tools/flyway-team/requirements.txt
python tools/flyway-team/check.py
```

Requires Python 3.10+, Git, `psql`, and local shared DB credentials. The helper fetches `origin/main`, verifies the configured shared target, and reads only `taskpilot_migrations.flyway_schema_history_v2`. Credentials remain in environment or ignored `.env`. Never use old `public.flyway_schema_history`, a dated report, or memory to infer the current version.

Report `checked_at_utc`, `deployed_version`, `main_version`, `local_version`, `new_local_versions`, `merged_not_deployed`, and `next_version`. The candidate is one above the highest shared/main/local version; the tool stops on incompatible history, checksum drift, duplicate numbers, or changed/missing accepted files. This is a planning/checksum check, not the Flyway engine's complete validate or an app-health check.

If DB access or fresh main is unavailable, report the live version as **unknown**. Offline CI may check Git but cannot allocate a verified live version. Draft SQL outside the active migration directory while access is resolved; do not guess a number or start the app against the shared DB.

## Two developers, one shared DB

1. Treat `next_version` as a **candidate, never a reservation**. Announce the chosen number in the feature PR and agree ordering with the other developer/coordinator. Two concurrent reads can propose the same number.
2. Before merge, update the branch from current main, run the helper again, and inspect open migration PRs. If an unapplied change collides, give it the next agreed number and test its SQL against the preceding migration. Recheck pending changes, not just the newly suggested number.
3. If the team's shared DB has already executed a conflicting migration, stop and report the conflict. Do not rename it, edit history/checksums, `repair`, reset the shared DB, or disable validation. Follow the recovery runbook for a reviewed reconciliation.
4. Use temporary test databases for unmerged SQL. Only the designated deployment executor/pipeline applies accepted migrations to the shared DB, in order. Starting the app runs Flyway automatically: do not boot a local branch with unmerged pending SQL against the shared target.

## Write and verify

- Add flat UTF-8 `V<number>__lowercase_description.sql` files only under `taskpilot-app/src/main/resources/db/reconciled`; use consecutive integers. Keep all SQL accepted on main immutable, even if deployment is pending. Preserve legacy `db/migration` for history only.
- Explicitly qualify business targets as `public`: e.g. `CREATE TABLE public.example`, `CREATE INDEX idx_example ON public.example`. The default Flyway schema is private metadata, not the business schema. The PostgreSQL 17 AST checker rejects unqualified/wrong targets, unsupported procedural/dynamic SQL and `.sql.conf`/non-SQL artifacts. Do not bypass it with comments, search_path changes or renaming files; extend the reviewed policy and real PostgreSQL tests for unsupported needs.
- The metadata schema has an explicit Flyway-object allowlist (history table, its known indexes/PK, row/array types), not an empty-schema rule. New business tables/types/sequences must never appear there. Catalog validation is mandatory even when Flyway checksums pass.
- Keep app validate enabled, baseline-on-migrate false, out-of-order false, and clean disabled. A numbering check is never permission to weaken these settings or run the adoption operator on an ordinary migration.
- Test both a fresh DB and an upgraded DB using the real Flyway engine, then app/integration behavior. For new columns/constraints, consider code still running against the shared schema; prefer compatible additions and backfills before destructive changes.
- After deployment, check actual runtime health and Flyway validate. Both developers then update their branches before local startup.
- Before merge require green `migration-source-check` and `migration-integration` on the current head, and verify both are actually required in the GitHub ruleset. Required workflows run for every PR, without path filtering. Docker missing, skipped/missing integration reports or absent fresh/upgrade backend cases are failures, not acceptable skips. RLS hardening remains a separate reviewed migration.

For collision examples, credentials, exact CI commands, and target changes, read [the team guide](../../../docs/development/FLYWAY_TEAM_WORKFLOW.md). The checker itself never writes SQL or deploys anything; creating a migration does not authorize applying it to the shared DB.
