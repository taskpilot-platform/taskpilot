-- Security hardening for Supabase PostgREST exposure.
-- Do not touch flyway_schema_history because Flyway is actively using it during migration.
DO $$
DECLARE
   t TEXT;


BEGIN
   FOREACH t IN ARRAY ARRAY [ 'system_settings',
   'users',
   'skills',
   'user_skills',
   'projects',
   'project_members',
   'sprints',
   'tasks',
   'comments',
   'notifications',
   'chat_sessions',
   'chat_messages',
   'ai_logs',
   'refresh_tokens',
   'password_reset_tokens' ]
   LOOP
      EXECUTE format(
         'ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',
         t
      );


-- Deny client roles by default when roles exist (Supabase).
IF to_regrole('anon') IS NOT NULL
AND to_regrole('authenticated') IS NOT NULL THEN EXECUTE format(
   'DROP POLICY IF EXISTS %I ON public.%I',
   'p_' || t || '_deny_client',
   t
);


EXECUTE format(
   'CREATE POLICY %I ON public.%I FOR ALL TO anon, authenticated USING (false) WITH CHECK (false)',
   'p_' || t || '_deny_client',
   t
);


END IF;


-- Keep service_role access when role exists (Supabase).
IF to_regrole('service_role') IS NOT NULL THEN EXECUTE format(
   'DROP POLICY IF EXISTS %I ON public.%I',
   'p_' || t || '_service_role',
   t
);


EXECUTE format(
   'CREATE POLICY %I ON public.%I FOR ALL TO service_role USING (true) WITH CHECK (true)',
   'p_' || t || '_service_role',
   t
);


END IF;


END
LOOP
;


END $$;