-- LAB ONLY. The database-name guard deliberately prevents execution on source DB.
-- Does not create/rename users or change their data. Rollback is the default.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '15s';
DO $$
DECLARE target text;
BEGIN
    IF current_database() !~ '^taskpilot_recovery_[a-z0-9_]+$' THEN
        RAISE EXCEPTION 'Lab-only rehearsal refused: unexpected database';
    END IF;
    IF to_regrole('anon') IS NULL OR to_regrole('authenticated') IS NULL THEN
        RAISE EXCEPTION 'Required simulated Supabase roles missing';
    END IF;
    FOREACH target IN ARRAY ARRAY['user_identities', 'project_meetings'] LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relname=target AND NOT c.relforcerowsecurity
              AND (pg_has_role(current_user,c.relowner,'USAGE')
                   OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)))
        ) THEN
            RAISE EXCEPTION 'Verify backend owner/bypass role before hardening %', target;
        END IF;
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('DROP POLICY IF EXISTS taskpilot_recovery_deny_client ON public.%I', target);
        -- RESTRICTIVE prevents another permissive policy from reopening client access.
        EXECUTE format('CREATE POLICY taskpilot_recovery_deny_client ON public.%I AS RESTRICTIVE FOR ALL TO anon, authenticated USING (false) WITH CHECK (false)', target);
        IF to_regrole('service_role') IS NOT NULL THEN
            EXECUTE format('DROP POLICY IF EXISTS taskpilot_recovery_service_access ON public.%I', target);
            EXECUTE format('CREATE POLICY taskpilot_recovery_service_access ON public.%I FOR ALL TO service_role USING (true) WITH CHECK (true)', target);
        END IF;
    END LOOP;
END $$;
SELECT c.relname, c.relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN ('user_identities','project_meetings');
-- Intentionally no commit switch. Production hardening needs its own review/migration.
ROLLBACK;
