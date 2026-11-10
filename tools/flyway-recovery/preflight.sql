-- Read-only inspection. Does not baseline, migrate, repair, clean or read user rows.
-- Historical incident diagnostic: queries legacy public history, not active v2.
-- Daily version selection uses tools/flyway-team/check.py; see README.md here.
\set ON_ERROR_STOP on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SHOW transaction_read_only;
SELECT current_database() AS database_name, current_user AS database_role,
       current_setting('server_version') AS postgres_version;
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user;
SELECT extname, extversion FROM pg_extension ORDER BY extname;
SELECT version, script, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank;

SELECT 'columns' AS component,
       md5(string_agg(concat_ws('|', table_name, column_name, data_type, udt_name,
                  character_maximum_length, is_nullable, column_default, is_identity,
                  identity_generation), E'\n' ORDER BY table_name, column_name)) AS fingerprint
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name NOT LIKE 'flyway%';

SELECT 'constraints' AS component,
       md5(string_agg(concat_ws('|', c.relname, k.conname, pg_get_constraintdef(k.oid)),
                     E'\n' ORDER BY c.relname, k.conname)) AS fingerprint
FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname NOT LIKE 'flyway%';

SELECT 'enum_values' AS component,
       md5(string_agg(concat_ws('|', t.typname, e.enumsortorder, e.enumlabel),
                     E'\n' ORDER BY t.typname, e.enumsortorder)) AS fingerprint
FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
JOIN pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = 'public';

SELECT indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' AND tablename NOT LIKE 'flyway%' ORDER BY tablename, indexname;
SELECT c.relname AS table_name, c.relrowsecurity AS rls_enabled,
       c.relforcerowsecurity AS rls_forced, pg_get_userbyid(c.relowner) AS owner
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname NOT LIKE 'flyway%'
ORDER BY c.relname;
SELECT tablename, policyname, permissive, roles, cmd, qual, with_check
FROM pg_policies WHERE schemaname = 'public' ORDER BY tablename, policyname;
SELECT table_name, grantee, privilege_type FROM information_schema.role_table_grants
WHERE table_schema = 'public' ORDER BY table_name, grantee, privilege_type;
SELECT routine_name, routine_type FROM information_schema.routines
WHERE routine_schema = 'public' ORDER BY routine_name;
ROLLBACK;
