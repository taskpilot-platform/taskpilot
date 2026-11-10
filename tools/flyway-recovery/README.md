# Historical recovery rehearsal tools

These files preserve the reproducible evidence from the 2026-11-09 investigation. They are **not the active application migration bundle or the production adoption procedure**.

- `prepare_bundle.py`: renders a rehearsal candidate from a trusted schema-only dump, without connecting to a DB. Its generated SQL predates the final guarded packaged V41/V42 bundle; never substitute its output into the application.
- `test_prepare_bundle.py`: seven offline safety tests for that historical generator.
- `preflight.sql`: read-only forensic metadata, including the **legacy** public history. It is not the current-version allocator.
- `rehearse_sensitive_rls.sql`: guarded lab-only security experiment; always rolls back, never a production migration.

For new migrations use the [shared DB workflow](../../docs/development/FLYWAY_TEAM_WORKFLOW.md) and `python tools/flyway-team/check.py`. For an explicitly planned recovery, use the [current runbook](../../docs/implementation/flyway/RECOVERY_RUNBOOK.md) and packaged `RecoveryAdoption` operator. See the [release evidence](../../docs/implementation/flyway/RECOVERY_RELEASE_2026-11-10.md) for what actually ran on the shared source.

Reproduce the historical offline unit checks with:

```sh
python -m unittest discover -s tools/flyway-recovery -p 'test_*.py'
```
