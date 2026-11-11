import json
import pathlib
import tempfile
import unittest
from prepare_bundle import filter_toc, render_snapshot, verify, sha256, check_v41_contract


class BundleSafetyTest(unittest.TestCase):
    def test_pre_v41_or_unrelated_schema_cannot_be_labeled_version_41(self):
        with self.assertRaises(ValueError):
            check_v41_contract("CREATE TABLE public.users(id bigint);")

    def test_legacy_history_is_excluded(self):
        text = "1; 0 0 TABLE public flyway_schema_history owner\n2; 0 0 TABLE public users owner"
        self.assertNotIn("flyway_schema_history", filter_toc(text))
        self.assertIn("TABLE public users", filter_toc(text))

    def test_data_dump_is_refused(self):
        for entry in ["1; 0 0 TABLE DATA public users owner", "1; 0 0 SEQUENCE SET public users_id_seq owner"]:
            with self.assertRaises(ValueError):
                filter_toc(entry)

    def test_psql_commands_and_session_settings_are_not_replayed(self):
        text = "\\connect source\nSET row_security = off;\nSELECT pg_catalog.set_config('search_path', '', false);\nCREATE TABLE public.users(id bigint);"
        result = render_snapshot(text)
        self.assertNotIn("\\connect", result)
        self.assertNotIn("row_security = off", result)
        self.assertIn("SET LOCAL lock_timeout", result)

    def test_custom_code_and_data_need_manual_review(self):
        for sql in ["CREATE FUNCTION public.fn() RETURNS void", "DROP TABLE public.users;", "INSERT INTO public.users VALUES(1);", "DO $$ BEGIN END $$;"]:
            with self.assertRaises(ValueError):
                render_snapshot(sql)

    def test_external_dependencies_refused(self):
        with self.assertRaises(ValueError):
            render_snapshot("CREATE TABLE public.x(id bigint REFERENCES auth.users(id));")

    def test_manifest_catches_changed_or_extra_migrations(self):
        with tempfile.TemporaryDirectory() as temp:
            out = pathlib.Path(temp)
            migrations = out / "migrations"
            migrations.mkdir()
            name = "V41__baseline.sql"
            (migrations / name).write_bytes(b"original")
            (out / "manifest.json").write_text(json.dumps({"files": {name: sha256(b"original")}}))
            verify(out)
            (migrations / name).write_bytes(b"changed")
            with self.assertRaises(ValueError):
                verify(out)
            (migrations / name).write_bytes(b"original")
            (migrations / "V43__extra.sql").write_text("SELECT 1;")
            with self.assertRaises(ValueError):
                verify(out)


if __name__ == "__main__":
    unittest.main()
