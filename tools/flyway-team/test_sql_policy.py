import unittest

import check
from test_check import BASE, history


class SqlPolicyTest(unittest.TestCase):
    def plan(self, sql):
        return check.plan({**BASE, "V43__schema_probe.sql": sql}, BASE, history())

    def test_supported_qualified_targets(self):
        for sql in [
            "CREATE TABLE public.example(id bigint PRIMARY KEY);",
            "CREATE INDEX idx_example ON public.example(id);",
            "CREATE UNIQUE INDEX idx_example ON public.example(lower(name)) WHERE name IS NOT NULL;",
            "ALTER TABLE public.example ADD COLUMN title text;",
            "CREATE TYPE public.state AS ENUM ('NEW','DONE');",
            "ALTER TYPE public.state ADD VALUE 'ARCHIVED';",
            "CREATE SEQUENCE public.example_seq;",
            "ALTER SEQUENCE public.example_seq OWNED BY public.example.id;",
            "CREATE VIEW public.example_view AS SELECT id FROM public.example;",
            "CREATE TABLE public.example_copy AS SELECT * FROM public.example;",
            "COMMENT ON COLUMN public.example.title IS 'public. is not a target';",
            "DROP INDEX public.idx_example;",
            "UPDATE public.example SET title=upper(title);",
            "WITH sample AS (SELECT id FROM public.example) SELECT * FROM sample;",
            'CREATE TABLE "public"."Mixed Case"(id bigint);',
            "CREATE TABLE public.example(id bigint GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME public.example_seq));",
            "SET LOCAL lock_timeout='5s'; CREATE TABLE public.example(id bigint);",
        ]:
            with self.subTest(sql=sql):
                self.assertEqual(self.plan(sql)["new_local_versions"], [43])

    def test_unqualified_and_wrong_schema_targets_are_rejected(self):
        for sql in [
            "CREATE TABLE example(id bigint);",
            "CREATE TABLE taskpilot_migrations.example(id bigint);",
            "CREATE INDEX public_in_name ON example(id);",
            "ALTER TABLE example ADD COLUMN title text;",
            "CREATE TYPE state AS ENUM ('NEW');",
            "CREATE SEQUENCE example_seq;",
            "CREATE VIEW example_view AS SELECT id FROM public.example;",
            "CREATE TABLE example_copy AS SELECT * FROM public.example;",
            "DROP TABLE example;",
            "ALTER SEQUENCE public.example_seq OWNED BY example.id;",
            "CREATE TABLE public.child(parent_id bigint REFERENCES parent(id));",
            "CREATE TABLE public.example(id bigint GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME taskpilot_migrations.hidden_seq));",
            "WITH sample AS (SELECT 1) DELETE FROM sample;",
            'CREATE TABLE "public.example"(id bigint);',
            "-- CREATE TABLE public.safe(id int);\nCREATE TABLE unsafe(id int);",
            "CREATE TABLE unsafe(title text DEFAULT 'public.example');",
        ]:
            with self.subTest(sql=sql), self.assertRaisesRegex(check.CheckError, "MIGRATION_SQL_POLICY"):
                self.plan(sql)

    def test_unanalysable_or_session_redirecting_sql_fails_closed(self):
        for sql in [
            "SET search_path=public; CREATE TABLE example(id bigint);",
            "SELECT set_config('search_path','public',false);",
            "DO $$ BEGIN EXECUTE 'CREATE TABLE hidden(id bigint)'; END $$;",
            "CREATE FUNCTION public.hidden() RETURNS void LANGUAGE plpgsql AS $$ BEGIN EXECUTE 'CREATE TABLE hidden(id int)'; END $$;",
            "CALL public.hidden();",
            "SELECT public.hidden();",
            "CREATE EXTENSION hstore;",
            "ALTER TABLE public.example SET SCHEMA taskpilot_migrations;",
            "BEGIN; CREATE TABLE public.example(id bigint); COMMIT;",
            "COPY public.example FROM PROGRAM 'anything';",
            "CREATE TABL public.broken(id int);",
        ]:
            with self.subTest(sql=sql), self.assertRaisesRegex(check.CheckError, "MIGRATION_SQL_POLICY"):
                self.plan(sql)

    def test_accepted_history_is_not_rewritten_or_retroactively_linted(self):
        self.assertEqual(check.plan(BASE, BASE, history())["local_version"], 42)


if __name__ == "__main__":
    unittest.main()
