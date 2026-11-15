# TaskPilot Phase Acceptance Gate (`tools/verification`)

`verify_phase.py` is the fail-closed Surefire report verifier for remediation phases. It complements `tools/flyway-team/verify_integration.py` (which remains untouched) by enforcing phase-scoped required cases, per-run correlation (`remediation.runId`), XML/filesystem freshness, and module isolation.

## Exact Invocation

Capture `--started-at` and a unique `--run-id` **before** running `mvn clean test`. Both steps are fail-fast: a nonzero Maven exit code **must** abort immediately so the phase gate never masks a build or non-manifest test failure.

### PowerShell

```powershell
$ErrorActionPreference = "Stop"
$runId = "p0-" + [DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$startedAt = [DateTimeOffset]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")

mvn clean test "-Dremediation.runId=$runId"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python tools/verification/verify_phase.py `
  --phase P0 `
  --reports . `
  --manifest tools/verification/phase-requirements.json `
  --run-id $runId `
  --started-at $startedAt
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

### Bash

```bash
set -euo pipefail
RUN_ID="p0-$(date -u +%Y%m%dT%H%M%SZ)"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

mvn clean test -Dremediation.runId="$RUN_ID" && \
python tools/verification/verify_phase.py \
  --phase P0 \
  --reports . \
  --manifest tools/verification/phase-requirements.json \
  --run-id "$RUN_ID" \
  --started-at "$STARTED_AT"
```

Always use `mvn clean test -Dremediation.runId=...` before running the gate so stale `target/` outputs are cleared and every Surefire XML carries `<property name="remediation.runId" value="..."/>`. `verify_phase.py` validates both the manifest-required cases and every discovered current-run `TEST-*.xml` suite across `taskpilot-*/target/surefire-reports` (reporting full checked test counts while ignoring old unrelated reports whose `mtime < started_at` and `remediation.runId != runId`).

## Freshness & Timezone Rules

- Every required `TEST-<Class>.xml` must have **both** filesystem `mtime >= started_at` and the current invocation's unique `remediation.runId`. Standard Surefire 3.0.2 XML omits `timestamp`; the gate does not manufacture one. If a producer supplies `<testsuite timestamp="...">`, it must also be fresh and valid.
- If Surefire emits a local naive `timestamp` (no `Z` or offset), `verify_phase.py` resolves it deterministically:
  1. Uses XML `<property name="user.timezone" value="..."/>` when present (supporting `UTC`/`GMT`/`±HH:MM` offsets and `zoneinfo.ZoneInfo` when host timezone data is available).
  2. Otherwise falls back to the current host local timezone.
- `--reports` accepts either a direct `surefire-reports` directory or the backend repository root (scanning only immediate `taskpilot-*/target/surefire-reports` directories, ignoring `node_modules` and unrelated `target/` files).

## Provider-Live Isolation

- Root `pom.xml` excludes `@Tag("provider-live")` by default via `<test.excludedGroups>provider-live</test.excludedGroups>`.
- Pure local builder/parsing tests remain in the default suite and must never read `.env` or contact external providers.
- To opt into live provider tests explicitly when credentials are configured:
  ```bash
  mvn test -Dtest.excludedGroups=none -Dgroups=provider-live -Dremediation.runId="$RUN_ID"
  ```

## Manifest Policy

`phase-requirements.json` currently defines only `P0`. Subsequent phases are added only when real product tests for those phases exist; querying an unknown phase fails closed with `UNKNOWN_PHASE`.

## Unit Tests

```bash
python -m unittest discover -s tools/verification -p 'test_*.py'
```
