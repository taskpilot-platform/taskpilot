"""Read-only shared Flyway planning. This neither migrates nor reserves a version."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zlib
from urllib.parse import parse_qs, unquote, urlparse

from sql_policy import SqlPolicyError, validate as validate_sql

BUNDLE = "taskpilot-app/src/main/resources/db/reconciled"
ROOT = Path(__file__).resolve().parents[2]
NAME = re.compile(r"V([1-9][0-9]*)__[a-z0-9][a-z0-9_]*\.sql\Z")
QUERY = """
BEGIN READ ONLY ISOLATION LEVEL REPEATABLE READ;
SET LOCAL statement_timeout='15s';
SELECT json_build_object(
  'rows', (SELECT json_agg(json_build_object(
    'version',version,'type',type,'script',script,'checksum',checksum,'success',success)
    ORDER BY installed_rank) FROM taskpilot_migrations.flyway_schema_history_v2),
  'baseline_comment',obj_description('taskpilot_migrations.flyway_schema_history_v2'::regclass));
COMMIT;
"""


class CheckError(Exception):
    pass


def normalized(sql):
    return sql.replace("\r\n", "\n")


def flyway_checksum(sql):
    # Flyway 11.3.2 ChecksumCalculator: first-line BOM removed, UTF-8 CRC32
    # over BufferedReader lines without CR/LF, converted to signed Java int.
    value = zlib.crc32(sql.removeprefix("\ufeff").replace("\r", "").replace("\n", "").encode("utf-8"))
    return value if value < 2**31 else value - 2**32


def migrations(files):
    found = {}
    for filename, sql in files.items():
        match = NAME.fullmatch(filename)
        if not match:
            raise CheckError("INVALID_MIGRATION_NAME: use flat V<number>__lowercase_description.sql files")
        version = int(match[1])
        if version in found:
            raise CheckError(f"DUPLICATE_VERSION: V{version}")
        found[version] = (filename, normalized(sql))
    return found


def plan(local_files, main_files, history):
    local, main = migrations(local_files), migrations(main_files)
    if not {41, 42}.issubset(main):
        raise CheckError("RECOVERY_CHAIN_MISSING: V41/V42 must remain present")
    for version, accepted in main.items():
        if local.get(version) != accepted:
            raise CheckError(f"ACCEPTED_MIGRATION_CHANGED: V{version}; update the branch, never rewrite accepted SQL")
    for collection in [main, local]:
        versions = sorted(collection)
        if versions[0] != 41 or any(b != a + 1 for a, b in zip(versions, versions[1:])):
            raise CheckError("NON_SEQUENTIAL_VERSION: update main and coordinate the next number")
    for version in sorted(set(local) - set(main)):
        try:
            validate_sql(local[version][1])
        except SqlPolicyError as error:
            raise CheckError(f"MIGRATION_SQL_POLICY: V{version}: {error}") from None
    deployed = None
    if history is not None:
        rows = history.get("rows") or []
        seen = []
        for row in rows:
            version = str(row.get("version", ""))
            if not re.fullmatch(r"[1-9][0-9]*", version) or row.get("success") is not True:
                raise CheckError("INVALID_SHARED_HISTORY: failed or unexpected version")
            version = int(version)
            if version not in main:
                raise CheckError("SHARED_HISTORY_NOT_IN_MAIN: do not renumber already-applied shared migrations")
            if row.get("type") == "BASELINE" and version == 41 and not seen:
                expected = "TaskPilot reviewed baseline SHA-256:" + hashlib.sha256(main[41][1].encode("utf-8")).hexdigest()
                if row.get("checksum") is not None or history.get("baseline_comment") != expected:
                    raise CheckError("UNBOUND_BASELINE: use the recovery runbook")
            elif row.get("type") != "SQL" or row.get("checksum") is None:
                raise CheckError("INVALID_SHARED_HISTORY: expected a successful SQL migration")
            elif row.get("script") != main[version][0]:
                raise CheckError("SHARED_HISTORY_NOT_IN_MAIN: applied filename conflicts with main")
            elif row["checksum"] != flyway_checksum(main[version][1]):
                raise CheckError(f"SHARED_CHECKSUM_MISMATCH: V{version}; investigate instead of repair/renumber")
            seen.append(version)
        if not seen or seen[0] != 41 or 42 not in seen or any(b != a + 1 for a, b in zip(seen, seen[1:])):
            raise CheckError("INVALID_SHARED_HISTORY: incomplete or unordered recovery history")
        deployed = seen[-1]
    return {
        "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "database_checked": history is not None,
        "deployed_version": deployed,
        "main_version": max(main),
        "local_version": max(local),
        "next_version": max(max(main), max(local), deployed or 0) + 1 if history is not None else None,
        "new_local_versions": sorted(set(local) - set(main)),
        "merged_not_deployed": [v for v in sorted(main) if deployed is not None and v > deployed],
        "number_reserved": False,
        "flyway_validate_run": False,
    }


def git(root, *args):
    try:
        result = subprocess.run(["git", *args], cwd=root, capture_output=True,
                                timeout=45, env={**os.environ, "GIT_TERMINAL_PROMPT": "0", "GCM_INTERACTIVE": "never"})
    except (OSError, subprocess.TimeoutExpired):
        raise CheckError("GIT_UNAVAILABLE_OR_TIMED_OUT") from None
    if result.returncode:
        raise CheckError("GIT_CHECK_FAILED: verify remote access and the requested base ref")
    return result.stdout.decode("utf-8-sig")


def sources(root, base_ref):
    legacy = "taskpilot-app/src/main/resources/db/migration"
    changed = git(root, "diff", "--name-only", base_ref, "--", legacy).splitlines()
    changed += git(root, "ls-files", "--others", "--exclude-standard", "--", legacy).splitlines()
    if changed:
        raise CheckError("LEGACY_MIGRATIONS_CHANGED: add new SQL only in db/reconciled")
    main = {}
    for path in git(root, "ls-tree", "-r", "--name-only", base_ref, "--", BUNDLE).splitlines():
        filename=path.removeprefix(BUNDLE + "/")
        if not NAME.fullmatch(filename):
            raise CheckError("UNSUPPORTED_MIGRATION_ARTIFACT: accepted bundle must contain flat versioned SQL only")
        main[filename] = git(root, "show", base_ref + ":" + path)
    local = {}
    for path in (root / BUNDLE).iterdir():
        if path.is_symlink():
            raise CheckError("MIGRATION_SYMLINK_NOT_SUPPORTED")
        if not path.is_file() or not NAME.fullmatch(path.name):
            raise CheckError("UNSUPPORTED_MIGRATION_ARTIFACT: only flat V<number>__description.sql files are supported")
        local[path.relative_to(root / BUNDLE).as_posix()] = path.read_text(encoding="utf-8-sig")
    return local, main


def connection_parts(url):
    try:
        parsed = urlparse(url.removeprefix("jdbc:"))
        if parsed.scheme != "postgresql" or not parsed.hostname or not parsed.path.strip("/"):
            raise ValueError()
        if parsed.username or parsed.password or any(k in parse_qs(parsed.query) for k in ["user", "password"]):
            raise ValueError()
        return parsed.hostname.lower(), parsed.port or 5432, unquote(parsed.path.lstrip("/")), parse_qs(parsed.query)
    except ValueError:
        raise CheckError("INVALID_DB_URL: use a PostgreSQL URL with credentials in separate variables") from None


def target_id(url, username):
    host, port, database, _ = connection_parts(url)
    if host.endswith(".pooler.supabase.com") and port == 6543:
        port = 5432
    return hashlib.sha256(f"{host}\n{port}\n{database}\n{username}".encode("utf-8")).hexdigest()


def credentials(env_file):
    values = {}
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip().removeprefix("export ")
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                value = value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                    value = value[1:-1]
                values[key.strip()] = value
    values.update(os.environ)
    result = {"DB_" + k: values.get("MIGRATION_SHARED_DB_" + k, values.get("DB_" + k))
              for k in ["URL", "USERNAME", "PASSWORD"]}
    if not all(result.values()):
        raise CheckError("MISSING_DB_CREDENTIALS: configure MIGRATION_SHARED_DB_* or DB_* in local environment/.env")
    return result


def read_shared(creds, expected_target):
    if target_id(creds["DB_URL"], creds["DB_USERNAME"]) != expected_target:
        raise CheckError("WRONG_SHARED_DATABASE: configured target does not match the repository fingerprint")
    host, port, database, query = connection_parts(creds["DB_URL"])
    sslmode = query.get("sslmode", ["require"])[0]
    if sslmode not in {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}:
        raise CheckError("INVALID_SSL_MODE")
    env = {**os.environ, "PGHOST": host, "PGPORT": str(port), "PGDATABASE": database,
           "PGUSER": creds["DB_USERNAME"], "PGPASSWORD": creds["DB_PASSWORD"],
           "PGSSLMODE": sslmode, "PGCONNECT_TIMEOUT": "10",
           "PGOPTIONS": "-c default_transaction_read_only=on -c statement_timeout=15000 -c lock_timeout=3000"}
    for key in ["PGSERVICE", "PGSERVICEFILE", "PGHOSTADDR"]:
        env.pop(key, None)
    try:
        result = subprocess.run(["psql", "-X", "-q", "-A", "-t", "--no-password", "-v", "ON_ERROR_STOP=1"],
                                input=QUERY, env=env, capture_output=True, text=True, encoding="utf-8", timeout=25)
    except (OSError, subprocess.TimeoutExpired):
        raise CheckError("PSQL_UNAVAILABLE_OR_TIMED_OUT: install PostgreSQL client and verify shared access") from None
    if result.returncode:
        # Raw libpq messages can include URLs and credentials; never echo stderr.
        raise CheckError("SHARED_DATABASE_QUERY_FAILED: verify access and active recovery history; no changes made")
    try:
        return json.loads(result.stdout.strip())
    except (ValueError, TypeError):
        raise CheckError("INVALID_SHARED_HISTORY_RESPONSE") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Git-only CI checks; cannot report the live next version")
    parser.add_argument("--base-ref", default="origin/main", help="Accepted migration source (CI: exact PR base SHA)")
    parser.add_argument("--no-fetch", action="store_true", help="Use an already-fetched ref; live recommendation may be stale")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env", help="Local credentials file; never printed")
    args = parser.parse_args()
    try:
        if not args.no_fetch:
            git(ROOT, "fetch", "--no-tags", "origin", "main:refs/remotes/origin/main")
        local, accepted = sources(ROOT, args.base_ref)
        plan(local, accepted, None)  # Reject source collisions before connecting.
        live = None
        if not args.offline:
            config = json.loads((ROOT / "tools/flyway-team/shared-target.json").read_text(encoding="utf-8"))
            live = read_shared(credentials(args.env_file), config["target_sha256"])
        result = plan(local, accepted, live)
        result["base_ref"] = args.base_ref
        result["base_commit"] = git(ROOT, "rev-parse", "--verify", args.base_ref).strip()
        result["remote_refreshed"] = not args.no_fetch
        result["notice"] = "Candidate only, not a reservation. Coordinate via PR and recheck before merge. This is not Flyway validate."
        if args.offline:
            result["notice"] = "OFFLINE: shared DB not checked; no live next version assigned. " + result["notice"]
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except CheckError as error:
        print(json.dumps({"ok": False, "error": str(error), "next_version": None}), file=sys.stderr)
        return 2
    except (OSError, ValueError, KeyError):
        print(json.dumps({"ok": False, "error": "CHECK_CONFIGURATION_FAILED", "next_version": None}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
