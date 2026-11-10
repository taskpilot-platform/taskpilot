# TaskPilot repository instructions

## Database and Flyway work

When a task changes schema/entity mappings, creates/reviews migrations, resolves Flyway errors, or starts a branch against the shared DB, first read [the standalone Flyway team skill](.agents/skills/taskpilot-flyway-team/SKILL.md). No Superpowers installation is required.

- Run `python tools/flyway-team/check.py` from this repository before assigning a version and again before merge. Its DB read is read-only; a candidate number is not reserved.
- The active bundle is `taskpilot-app/src/main/resources/db/reconciled`; active metadata is `taskpilot_migrations.flyway_schema_history_v2`. Do not infer current state from archived reports or legacy history.
- Accepted migrations are immutable. Keep validate enabled, automatic baseline disabled, out-of-order disabled, and clean disabled.
- Local app startup automatically migrates. Never use it to try unmerged SQL against the shared deployment DB. Coordinate version ordering through PRs; deployment applies accepted SQL.
- If shared access is unavailable or the checker finds a conflict, report the missing evidence/conflict; do not guess a live version, alter history, or weaken validation.

Human workflow and troubleshooting: [docs/development/FLYWAY_TEAM_WORKFLOW.md](docs/development/FLYWAY_TEAM_WORKFLOW.md). Tasks unrelated to database work do not need a DB connection.
