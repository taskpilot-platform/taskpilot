# Shared Flyway workflow implementation plan

> **For agentic workers:** Use the existing checkout and execute these tasks in order; retain unrelated recovery reports and progress edits.

**Goal:** Let both developers and agents inspect the live shared migration version, choose a candidate number, and avoid silently changing deployed migrations.

**Architecture:** Repository-local skill and AGENTS.md route to one human-readable workflow and one Python standard-library checker. The checker reads Git and Flyway metadata only; it never starts Spring, migrates, reserves a version, or needs shared credentials in CI. A non-secret target fingerprint prevents accidentally querying a local DB as the shared deployment DB.

**Spec:** User requests repository-shared rules and a live version check for two developers using the same database. Retain the existing reconciled Flyway policy and deployment behavior.

## Tasks

- [x] Add failing checker tests: shared DB/main/local maxima, concurrent same-version files, immutable accepted SQL, stale branches, unknown/failed history, wrong target, read-only SQL and secret-free errors.
- [x] Implement the checker and test against the shared DB with a read-only query. Report a timestamped candidate, explicitly not a reservation or full Flyway validate.
- [x] Add the skill, AGENTS.md routing, team instructions and README links. Document one deployment executor, pre-merge recheck and missing-access behavior.
- [x] Add an offline PR CI guard without database credentials, run its tests and schema checks, validate the skill and forward-test a realistic agent scenario.
- [x] Commit isolated workflow changes and publish the reviewable branch so the teammate can use the same files. No source DB writes or application rollout for this task.

## Checks

DB42/main43/local44 suggests45; two simultaneous readers can still suggest the same number. A DB version or applied filename absent from current main is a conflict, not permission to rename history. Offline CI does not pretend to inspect deployment. No credential or JDBC URL is printed or committed.

Verification: 20 checker tests pass; source-only CI command passes; live read-only query observes shared/main/local42 and candidate43. Three vectors (reviewed V41, V42, Unicode/BOM/CRLF) match the actual Flyway11.3.2 checksum calculator. Skill frontmatter and links validate. An independent agent without Superpowers correctly retains existing V44 when next45 refers to an additional file, rejects stale evidence and refuses startup on the shared DB. Its inherited PGHOSTADDR finding was reproduced as a failing regression, fixed and verified. No application runtime/configuration or shared database writes were changed by this task.

User follow-up authorizes committing the remaining incident/progress reports and keeping the Git tree clean. Preserve dated evidence with current-release banners; retain historical rehearsal tools only with explicit archive labeling. Remove the unused stale email draft, generalize machine-local evidence paths, and keep real dumps/logs/credentials out of Git. The separate report repository and frontend working tree were already clean.
