#!/usr/bin/env python3
"""HISTORICAL 2026-11-09: prepare an OFFLINE rehearsal bundle, not the active chain.

Never reads .env, connects to a database, modifies history, or authorizes deploy.
pg_restore is only invoked WITHOUT --dbname, so it renders files, not a database.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile

def sha256(content):
    return hashlib.sha256(content).hexdigest()


def filter_toc(text):
    if re.search(r"\bTABLE DATA\b|\bSEQUENCE SET\b", text):
        raise ValueError("Only a schema-only dump is accepted; data entries found")
    return "\n".join(line for line in text.splitlines()
                     if "flyway_schema_history" not in line
                     and " SCHEMA - public " not in line
                     and " COMMENT - SCHEMA public " not in line) + "\n"


def render_snapshot(sql):
    # Accept only reviewed pg_dump output, not SQL supplied by an untrusted party.
    # Refuse procedural/custom code rather than pretending to sanitize arbitrary SQL.
    forbidden = r"(?im)^\s*(?:CREATE\s+(?:(?:OR\s+REPLACE)\s+)?(?:FUNCTION|PROCEDURE|TRIGGER|EVENT\s+TRIGGER|FOREIGN\s+TABLE|SERVER)|ALTER\s+SYSTEM|DROP\s+|COPY\s+|INSERT\s+|UPDATE\s+|DELETE\s+|DO\s+\$)"
    if re.search(forbidden, sql):
        raise ValueError("Dump includes data, destructive or custom procedural SQL; manual review required")
    lines = []
    for line in sql.splitlines():
        if line.startswith("\\") or line.startswith("--") or not line.strip():
            continue
        if line.startswith("SET ") or line.startswith("SELECT pg_catalog.set_config("):
            continue
        lines.append(line)
    body = "\n".join(lines)
    if re.search(r"(?i)\bflyway_schema_history\b", body):
        raise ValueError("Legacy history unexpectedly included")
    if re.search(r"(?i)\b(?:auth|storage|vault|realtime|graphql)\.", body):
        raise ValueError("External Supabase schema dependencies need a separate review")
    return ("-- REHEARSAL CANDIDATE ONLY. No source data or historical checksum repair.\n"
            "SET LOCAL lock_timeout = '5s';\n"
            "SET LOCAL statement_timeout = '60s';\n"
            "CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;\n"
            "CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;\n"
            'CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;\n'
            + body + "\n")


def check_v41_contract(sql):
    required = ["CREATE TABLE public.users", "CREATE TABLE public.projects",
                "CREATE TABLE public.tasks", "CREATE TABLE public.project_meetings",
                "CREATE TABLE public.user_identities",
                "CREATE UNIQUE INDEX idx_users_email_lower_trimmed ON public.users"]
    if any(token not in sql for token in required):
        raise ValueError("Snapshot lacks required V41 objects; refusing to label it version 41")


def prepare(schema_dump, output, baseline_version):
    if baseline_version != 41:
        raise ValueError("This reviewed incident protocol currently targets version 41 only")
    if output.exists():
        raise ValueError("Output already exists; refusing to overwrite artifacts")
    toc = subprocess.run(["pg_restore", "--list", str(schema_dump)], check=True,
                         capture_output=True, text=True, encoding="utf-8").stdout
    filtered = filter_toc(toc)
    with tempfile.TemporaryDirectory(prefix="taskpilot-recovery-", dir=output.parent) as temp:
        listing = pathlib.Path(temp) / "restore.list"
        listing.write_text(filtered, encoding="utf-8")
        sql = subprocess.run(["pg_restore", "--no-owner", "--no-privileges",
                              "--use-list", str(listing), "--file", "-", str(schema_dump)],
                             check=True, capture_output=True, text=True, encoding="utf-8").stdout
    snapshot = render_snapshot(sql)
    check_v41_contract(snapshot)
    # Forward-only index addition. Never execute the V41 email UPDATE again.
    forward = ("-- Recovery candidate: preserve existing schedule values.\n"
               "SET LOCAL lock_timeout = '5s';\n"
               "SET LOCAL statement_timeout = '30s';\n"
               'ALTER TABLE public.project_meetings\n'
               '  ADD COLUMN IF NOT EXISTS scheduled_start_time TIMESTAMPTZ,\n'
               '  ADD COLUMN IF NOT EXISTS scheduled_end_time TIMESTAMPTZ;\n'
               'CREATE INDEX IF NOT EXISTS idx_project_meetings_scheduled_start\n'
               '  ON public.project_meetings(scheduled_start_time);\n')
    artifacts = {"V41__reviewed_schema_baseline.sql": snapshot,
                 "V42__reconcile_meeting_schedule.sql": forward}
    output.mkdir()
    migrations = output / "migrations"
    migrations.mkdir()
    for name, content in artifacts.items():
        (migrations / name).write_bytes(content.encode("utf-8"))
    manifest = {"format": 1, "reviewed_for_production": False,
                "baseline_version": "41", "history_table": "flyway_schema_history_v2",
                "source_dump_sha256": sha256(schema_dump.read_bytes()),
                "files": {name: sha256(content.encode("utf-8")) for name, content in artifacts.items()},
                "limitations": ["public schema only; no owner/ACL or application data",
                                "requires review of roles, extensions, seeds and schema fingerprint",
                                "version numbers require team reservation before release",
                                "no baseline-on-migrate; explicit adoption required"]}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print("Prepared offline REHEARSAL bundle. No database connection was made.")
    return manifest


def verify(output):
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    names = manifest["files"]
    actual = {f.name for f in (output / "migrations").iterdir() if f.is_file()}
    if actual != set(names):
        raise ValueError("Unexpected or missing migration artifacts")
    for name, checksum in names.items():
        if pathlib.Path(name).name != name or not re.fullmatch(r"V\d+__[A-Za-z0-9_]+\.sql", name):
            raise ValueError("Unsafe migration filename")
        if sha256((output / "migrations" / name).read_bytes()) != checksum:
            raise ValueError("Artifact checksum changed: " + name)
    print("Artifact SHA-256 verified; this is NOT production deployment approval.")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema-dump", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        verify(args.output)
    else:
        if not args.schema_dump:
            parser.error("--schema-dump is required for offline preparation")
        prepare(args.schema_dump.resolve(), args.output.resolve(), 41)


if __name__ == "__main__":
    main()
