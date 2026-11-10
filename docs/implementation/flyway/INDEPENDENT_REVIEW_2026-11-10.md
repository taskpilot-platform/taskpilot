# Independent Flyway verification — 2026-11-10

> **Historical review:** this records findings before the final fixes. Source adoption and deployment subsequently completed; see [the verified release outcome](RECOVERY_RELEASE_2026-11-10.md). The original verdict below is not the current release status.

> **Follow-up:** The three findings below were subsequently addressed in the working tree, with additional negative tests and a new regression run. See [repair implementation and current evidence](REVIEW_FIXES_2026-11-10.md). This original report remains the before-fix record; neither report claims that source cutover has occurred.

## Verdict

**Not yet approved for source adoption/deployment.** The latest working tree builds in isolation and its existing regression tests pass, including the private-history changes. Additional negative experiments exposed an incomplete email-index check and an application-schema validation gap. The recovery profile/runbook also contradict the new unconditional migration policy.

This verification did not connect to or write to the source database, deploy, push, repair history, modify existing application/migration code, or change existing dirty files. Only this report was added to the repository. All extra harnesses and synthetic databases are local lab artifacts.

## Reviewed revision and build input

- Branch: `fix/flyway-safe-recovery`; HEAD: `2bdb94b`, plus existing uncommitted/untracked changes, especially `MigrationDatabaseConfiguration.java`.
- Current source, tests, resources and Maven POMs were copied without `.env`, `.git` or existing `target` directories into a separate build directory. A byte comparison found **zero source differences** between that snapshot and the working tree.
- Build-input manifest SHA-256: `b64a72af9d74bc7388ad6e97d1ae0d7e9430db4c817a4b75ab5975e84260da56` (sorted relative paths plus SHA-256 of each source/POM file).
- This is a dirty-tree verification, not approval of the committed revision alone. The isolated JAR prints commit `unknown` because its snapshot intentionally has no Git metadata.

## Findings

### 1. High: wrong email-index definition passes the adoption guard

**Location:** `taskpilot-app/src/main/java/com/taskpilot/app/migration/RecoveryAdoption.java:118`, used at `:194`.

`emailUniqueValid` only checks `indisvalid AND indisunique` for the named index. It does not establish that it indexes `public.users`, uses `lower(trim(email))`, covers all rows, or is ready for writes.

Reproduction on a new synthetic V41 database:

1. Replace `idx_users_email_lower_trimmed` with a unique index on `public.users(id)` using the same name.
2. Inspect and pass the resulting approval to the real `RecoveryAdoption.adopt()`.
3. Run the real `validateComplete()`.
4. Insert `case@example.invalid` and `CASE@example.invalid` into `users`.

Observed results:

```text
WRONG_EMAIL_INDEX_REPORTED_VALID=true
WRONG_EMAIL_INDEX_ADOPTED_AND_CASE_DUPLICATES_STORED=true
```

The raw email constraint still permits this case variant. This does **not** establish that the source index is currently wrong; it establishes that the advertised safety check cannot detect this drift. Validate the full index definition, including relation, expression, predicate, readiness and access method, before any baseline write. Add negative tests for wrong expression/relation and partial indexes.

### 2. Medium: an approval binds observed drift, not compatibility with the reviewed schema

**Locations:** `RecoveryAdoption.java:111-119`, `:192-195`, `:182-184`.

The catalog digest detects changes **after** inspection. It is not compared with an independently reviewed compatibility contract. `validateComplete()` validates migration inventory/history/checksums, not all application columns.

Reproduction:

1. Create a synthetic V41 database and drop `public.users.avatar_url`.
2. Inspect that state, approve it, adopt and validate it.
3. Start the newly built complete backend against that database.

Observed:

```text
MISSING_APPLICATION_COLUMN_ADOPTED_AND_VALIDATED=true
Schema validation: missing column [avatar_url] in table [users]
```

Hibernate safely refuses startup, but only **after** the new baseline and V42 have committed. Manual catalog review is already required by the old runbook; these experiments show that the operator output does not replace that review. Before cutover, require independently reviewed expected schema/differences and application compatibility validation on a restored copy of the exact target. Ideally enforce that contract before creating the new history.

### 3. Medium: recovery-profile behavior and operating instructions are stale

**Locations:** `MigrationDatabaseConfiguration.java:44-59`; `taskpilot-app/src/main/resources/application-recovery.yml:14`; `docs/implementation/flyway/RECOVERY_RUNBOOK.md:53-61`.

The configuration customizer unconditionally sets `classpath:db/reconciled`, overriding an external recovery location. A direct executable check of the actual customizer produced:

```text
CONFIGURED_EXTERNAL_LOCATION_OVERRIDDEN=[classpath:db/reconciled]
CONFIGURED_HISTORY_SCHEMA=taskpilot_migrations
```

The profile still declares that an external bundle is mandatory; its test only reads YAML and does not exercise effective configuration. The runbook still directs operators to an external bundle/unqualified v2 table instead of documenting the sealed packaged bundle, private schema and guarded operator CLI.

Choose one supported contract, remove or clearly supersede the obsolete profile/tooling path, and test effective Spring/Flyway configuration. Document cutover order explicitly: the new default configuration must not be deployed to an existing unadopted source under the assumption that recovery remains opt-in. A raw manual baseline also lacks the comment binding now required by startup.

## Fresh verification results

### Existing automated suites

Focused command:

```powershell
mvn -B -o -pl taskpilot-app -am test '-Dtest=ReconciledFlywayIntegrationTest,MigrationConnectionSafetyTest,RecoveryDefaultConfigurationTest,RecoveryProfileSafetyTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- **18 tests passed**, zero failures/errors/skips.
- Includes **13 actual PostgreSQL integration tests**, not Docker-disabled skips.
- Covers successful fresh/adopted migration, wrong schedule definitions, history ACLs, resume, callback exclusion, false V42 baseline and blocking/releasing a writer.

Broad command:

```powershell
mvn -B -o package '-Dtest=*,!GoogleDriveLiveCheckTest,!GoogleAiEmbeddingModelVerificationTest,!GeminiBatchQuotaExperimentTest,!RealDocumentPipelineVerificationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Fresh Surefire reports, excluding old reports from the four explicitly excluded live-provider classes:

| Module | Tests | Failures / errors / skipped |
|---|---:|---|
| infrastructure | 24 | 0 / 0 / 0 |
| contracts | 42 | 0 / 0 / 0 |
| users | 16 | 0 / 0 / 0 |
| ai | 236 | 0 / 0 / 0 |
| projects | 41 | 0 / 0 / 0 |
| app | 32 | 0 / 0 / 0 |
| **Total** | **391** | **0 / 0 / 0** |

The 18 focused tests are included in this total, not additional tests. All test phases passed, but this command's final packaging phase failed to rename the existing application JAR to `.jar.original` on Windows. No existing process was terminated to force that operation.

An isolated build from the identical source snapshot then ran `mvn -B -o package -DskipTests` and **all modules packaged successfully**, including Spring Boot repackage. This second command did not rerun tests; the test results above are from the original working tree.

Python recovery tooling: **7/7 passed**.

### Complete backend smoke using the new isolated artifact

Both containers used the internal-only lab network, dummy external-provider credentials and synthetic data. No source `.env` was mounted. No Flyway table/location CLI overrides were supplied: this exercises the actual default migration policy and startup strategy.

| Check | Fresh database | Synthetic existing V41 database, explicitly adopted |
|---|---|---|
| Effective history schema | `taskpilot_migrations` | `taskpilot_migrations` |
| Effective migration location | `classpath:db/reconciled` | `classpath:db/reconciled` |
| Current migration version | 42 | 42 |
| Health | 200 | 200 |
| Register | 201 | 201 |
| Login | 200 | 200 |
| Authenticated profile | 200 | 200 |
| Case-variant duplicate | 409 JSON | 409 JSON |
| Process exit | 0 | 0 |

These are **not** fresh production-data restore or browser/end-to-end tests. Earlier real-backup/browser evidence predates the latest private-schema edits and was not promoted to fresh evidence here.

### Additional failure/retry experiment

An incorrect schedule index made adoption fail after the baseline had committed. After correcting the index, the old approval was rejected with `PREFLIGHT_MISMATCH`. Re-inspection followed by approval and adoption succeeded, and final validation passed. The interrupted-baseline retry path therefore worked in this tested case.

## Remaining boundaries

- Session-pooler rewriting and separate login/database-role binding passed local tests. This is not a live Supabase session-pooler/authentication test.
- No new verification of source adoption, current source schema, full Supabase backup/PITR, remote deployment or frontend/browser flows was performed.
- Fresh-baseline ACL hardening does not imply adopted existing application tables have been hardened. Existing source RLS concerns remain a separate security work item.
- The write-freeze connection has a 45-second idle-in-transaction timeout (`RecoveryAdoption.java:133`), while inspection/migration/verification run on other connections. Its final liveness check detects expiry but does not make independently committed work atomic. This needs a slow-path rehearsal and explicit maintenance/retry handling before promising an uninterrupted freeze or zero downtime; the fast local tests do not prove that property.
- Future migrations require revisiting the hard-coded approved script list, exact two-row history checks and V42 completion rule. Do not append V43 and assume ordinary Flyway startup will accept it unchanged.

## Evidence location

`%TEMP%/opencode/taskpilot-independent-review-20261110/`

- `focused-tests.log`, `regression.log`, `isolated-build.log`
- `negative-tests.log`, `configuration-policy.log`, `prepare-smoke.log`
- `current-app-fresh.log`, `current-app-existing.log`, `current-app-missing-column.log`
- `IndependentRecoveryAudit.java`, `ConfigurationPolicyAudit.java`, `PrepareSmokeDatabases.java`, `CurrentAppSmoke.java`
- `source-snapshot/`, `current-artifact/`

Temporary synthetic databases are named `independent_review_*` in the pre-existing local lab container. Successful smoke containers are `taskpilot-independent-fresh-20261110` and `taskpilot-independent-existing-20261110`; the intentionally failing missing-column container is `taskpilot-independent-missing-column-20261110`. Existing containers and user work were not removed.
