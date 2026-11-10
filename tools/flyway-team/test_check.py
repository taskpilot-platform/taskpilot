import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zlib

import check


V41 = "V41__reviewed_schema_baseline.sql"
V42 = "V42__reconcile_meeting_schedule.sql"
BASE = {V41: "CREATE TABLE example(id bigint);\n", V42: "CREATE INDEX example_idx ON example(id);\n"}


def history(version=42):
    rows = [{"version": "41", "type": "BASELINE", "script": "<< Flyway Baseline >>", "checksum": None, "success": True}]
    for v in range(42, version + 1):
        sql = BASE[V42] if v == 42 else f"SELECT {v};"
        crc = zlib.crc32(sql.rstrip("\n").encode())
        rows.append({"version": str(v), "type": "SQL", "script": V42 if v == 42 else f"V{v}__new.sql",
                     "checksum": crc if crc < 2**31 else crc - 2**32, "success": True})
    return {"rows": rows, "baseline_comment": "TaskPilot reviewed baseline SHA-256:" + hashlib.sha256(BASE[V41].encode()).hexdigest()}


class PlanningTest(unittest.TestCase):
    def test_uses_database_main_and_local_not_just_deployed_version(self):
        main = {**BASE, "V43__new.sql": "SELECT 43;"}
        local = {**main, "V44__new.sql": "SELECT 44;"}
        result = check.plan(local, main, history())
        self.assertEqual((result["deployed_version"], result["main_version"], result["local_version"], result["next_version"]), (42, 43, 44, 45))
        self.assertEqual(result["merged_not_deployed"], [43])
        self.assertFalse(result["number_reserved"])

    def test_offline_does_not_claim_a_database_version_or_live_candidate(self):
        result = check.plan(BASE, BASE, None)
        self.assertFalse(result["database_checked"])
        self.assertIsNone(result["deployed_version"])
        self.assertIsNone(result["next_version"])

    def test_duplicate_version_in_local_is_rejected(self):
        with self.assertRaisesRegex(check.CheckError, "DUPLICATE_VERSION"):
            check.plan({**BASE, "V43__alice.sql": "SELECT 1;", "V43__bob.sql": "SELECT 2;"}, BASE, history())

    def test_accepted_migration_cannot_be_edited(self):
        with self.assertRaisesRegex(check.CheckError, "ACCEPTED_MIGRATION_CHANGED"):
            check.plan({**BASE, V42: "DROP TABLE example;"}, BASE, history())

    def test_accepted_migration_cannot_be_removed_or_renamed(self):
        for local in [{V41: BASE[V41]}, {V41: BASE[V41], "V42__renamed.sql": BASE[V42]}]:
            with self.assertRaisesRegex(check.CheckError, "ACCEPTED_MIGRATION_CHANGED"):
                check.plan(local, BASE, history())

    def test_crlf_differences_are_not_schema_changes(self):
        self.assertEqual(check.plan({k: v.replace("\n", "\r\n") for k, v in BASE.items()}, BASE, history())["next_version"], 43)

    def test_stale_branch_must_include_new_main_migration(self):
        with self.assertRaisesRegex(check.CheckError, "ACCEPTED_MIGRATION_CHANGED"):
            check.plan(BASE, {**BASE, "V43__new.sql": "SELECT 43;"}, history())

    def test_database_ahead_of_main_is_not_silently_accepted(self):
        with self.assertRaisesRegex(check.CheckError, "SHARED_HISTORY_NOT_IN_MAIN"):
            check.plan(BASE, BASE, history(43))

    def test_applied_shared_script_conflicting_with_main_is_rejected(self):
        live = history(); live["rows"][-1]["script"] = "V42__someone_else.sql"
        with self.assertRaisesRegex(check.CheckError, "SHARED_HISTORY_NOT_IN_MAIN"):
            check.plan(BASE, BASE, live)

    def test_same_filename_with_different_applied_sql_is_rejected(self):
        live = history(); live["rows"][-1]["checksum"] += 1
        with self.assertRaisesRegex(check.CheckError, "SHARED_CHECKSUM_MISMATCH"):
            check.plan(BASE, BASE, live)

    def test_checksum_preserves_unicode_but_ignores_bom_and_line_endings(self):
        expected = check.flyway_checksum("-- ti\u1ebfng Vi\u1ec7t\nSELECT 1;\n")
        self.assertEqual(expected, check.flyway_checksum("\ufeff-- ti\u1ebfng Vi\u1ec7t\r\nSELECT 1;\r\n"))
        self.assertNotEqual(expected, check.flyway_checksum("-- ti\u1ebfng Vi\u1ec7t\nSELECT 2;\n"))

    def test_failed_history_and_unbound_baseline_are_rejected(self):
        live = history(); live["rows"][-1]["success"] = False
        with self.assertRaisesRegex(check.CheckError, "INVALID_SHARED_HISTORY"):
            check.plan(BASE, BASE, live)
        live = history(); live["baseline_comment"] = "unreviewed"
        with self.assertRaisesRegex(check.CheckError, "UNBOUND_BASELINE"):
            check.plan(BASE, BASE, live)

    def test_non_integer_or_callback_migrations_are_rejected(self):
        for name in ["V43_1__new.sql", "V043__new.sql", "R__repeat.sql", "afterMigrate.sql"]:
            with self.assertRaisesRegex(check.CheckError, "INVALID_MIGRATION_NAME"):
                check.plan({**BASE, name: "SELECT 1;"}, BASE, history())

    def test_missing_migration_number_requires_recheck(self):
        with self.assertRaisesRegex(check.CheckError, "NON_SEQUENTIAL_VERSION"):
            check.plan({**BASE, "V44__new.sql": "SELECT 44;"}, BASE, history())


class ReadOnlyTest(unittest.TestCase):
    def test_credentials_fingerprint_accepts_supabase_session_and_transaction_endpoints(self):
        a = check.target_id("jdbc:postgresql://example.pooler.supabase.com:6543/postgres?prepareThreshold=0", "postgres.project")
        b = check.target_id("jdbc:postgresql://example.pooler.supabase.com:5432/postgres", "postgres.project")
        self.assertEqual(a, b)
        self.assertNotEqual(a, check.target_id("jdbc:postgresql://localhost:5432/postgres", "postgres.project"))

    def test_wrong_target_fails_before_any_database_connection(self):
        creds = {"DB_URL": "jdbc:postgresql://localhost:5432/wrong", "DB_USERNAME": "lab", "DB_PASSWORD": "secret"}
        with patch.object(check.subprocess, "run") as run:
            with self.assertRaisesRegex(check.CheckError, "WRONG_SHARED_DATABASE"):
                check.read_shared(creds, "0" * 64)
            run.assert_not_called()

    def test_query_is_readonly_and_password_is_not_in_command_arguments(self):
        creds = {"DB_URL": "jdbc:postgresql://example.pooler.supabase.com:6543/postgres", "DB_USERNAME": "postgres.project", "DB_PASSWORD": "secret-never-print"}
        completed = subprocess.CompletedProcess([], 0, json.dumps(history()), "")
        with patch.object(check.subprocess, "run", return_value=completed) as run:
            self.assertEqual(check.read_shared(creds, check.target_id(creds["DB_URL"], creds["DB_USERNAME"])), history())
            args, kwargs = run.call_args
            self.assertNotIn(creds["DB_PASSWORD"], " ".join(args[0]))
            self.assertIn("default_transaction_read_only=on", kwargs["env"]["PGOPTIONS"])
            self.assertIn("BEGIN READ ONLY", kwargs["input"])
            self.assertNotRegex(kwargs["input"], r"(?i)\b(INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE|GRANT|REVOKE)\b")

    def test_psql_error_does_not_expose_credentials_or_raw_stderr(self):
        creds = {"DB_URL": "jdbc:postgresql://db.invalid:5432/postgres", "DB_USERNAME": "lab", "DB_PASSWORD": "secret-never-print"}
        completed = subprocess.CompletedProcess([], 2, "", "secret-never-print postgres://user:password@private-host")
        with patch.object(check.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(check.CheckError, "SHARED_DATABASE_QUERY_FAILED") as caught:
                check.read_shared(creds, check.target_id(creds["DB_URL"], creds["DB_USERNAME"]))
            self.assertNotIn("secret", str(caught.exception))
            self.assertNotIn("private-host", str(caught.exception))

    def test_inherited_host_address_cannot_redirect_the_verified_target(self):
        creds = {"DB_URL": "jdbc:postgresql://db.invalid:5432/postgres", "DB_USERNAME": "lab", "DB_PASSWORD": "test-only"}
        completed = subprocess.CompletedProcess([], 0, json.dumps(history()), "")
        with patch.dict(check.os.environ, {"PGHOSTADDR": "192.0.2.99", "PGSERVICE": "other-db", "PGSERVICEFILE": "other.conf"}):
            with patch.object(check.subprocess, "run", return_value=completed) as run:
                check.read_shared(creds, check.target_id(creds["DB_URL"], creds["DB_USERNAME"]))
                env = run.call_args.kwargs["env"]
                self.assertEqual(env["PGHOST"], "db.invalid")
                self.assertNotIn("PGHOSTADDR", env)
                self.assertNotIn("PGSERVICE", env)
                self.assertNotIn("PGSERVICEFILE", env)


class GitIntegrationTest(unittest.TestCase):
    def test_real_git_base_and_local_files_are_checked_without_database(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=True).stdout.strip()
            git("init"); git("config", "user.email", "fixture@example.invalid"); git("config", "user.name", "Fixture")
            bundle = root / check.BUNDLE; bundle.mkdir(parents=True)
            for name, sql in BASE.items(): (bundle / name).write_text(sql, encoding="utf-8")
            git("add", "."); git("commit", "-m", "fixture baseline"); base = git("rev-parse", "HEAD")
            (bundle / "V43__new.sql").write_text("SELECT 43;", encoding="utf-8")
            local, main = check.sources(root, base)
            result = check.plan(local, main, None)
            self.assertEqual(result["new_local_versions"], [43])
            self.assertEqual(result["main_version"], 42)
            wrong = root / "taskpilot-app/src/main/resources/db/migration/V44__wrong_folder.sql"
            wrong.parent.mkdir(parents=True)
            wrong.write_text("SELECT 44;", encoding="utf-8")
            with self.assertRaisesRegex(check.CheckError, "LEGACY_MIGRATIONS_CHANGED"):
                check.sources(root, base)


if __name__ == "__main__":
    unittest.main()
