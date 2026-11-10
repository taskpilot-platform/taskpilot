-- Forward-only reconciliation. Never replay the V41 user-email rewrite.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
    IF (SELECT count(*) FROM pg_attribute
        WHERE attrelid='public.project_meetings'::regclass AND NOT attisdropped
          AND attname IN ('scheduled_start_time','scheduled_end_time')
          AND atttypid='timestamptz'::regtype AND atttypmod IN (-1,6)
          AND NOT attnotnull AND NOT atthasdef AND attidentity='' AND attgenerated='') <> 2 THEN
        RAISE EXCEPTION 'Recovery refused: unexpected schedule column type or attributes';
    END IF;
    IF to_regclass('public.idx_project_meetings_scheduled_start') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM pg_index i
           JOIN pg_class idx ON idx.oid=i.indexrelid
           JOIN pg_am am ON am.oid=idx.relam
           JOIN pg_opclass opc ON opc.oid=i.indclass[0]
           JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attname='scheduled_start_time'
           WHERE i.indexrelid=to_regclass('public.idx_project_meetings_scheduled_start')
             AND i.indrelid='public.project_meetings'::regclass
             AND i.indisvalid AND i.indisready AND NOT i.indisunique
             AND i.indnkeyatts=1 AND i.indnatts=1 AND i.indkey[0]=a.attnum
             AND i.indoption[0]=0 AND i.indcollation[0]=0
             AND opc.opcdefault AND opc.opcintype='timestamptz'::regtype
             AND i.indexprs IS NULL AND i.indpred IS NULL AND am.amname='btree'
       ) THEN
        RAISE EXCEPTION 'Recovery refused: unexpected schedule index definition';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_project_meetings_scheduled_start
    ON public.project_meetings(scheduled_start_time);

-- Supabase default grants must not expose writable migration metadata to clients.
REVOKE ALL ON TABLE taskpilot_migrations.flyway_schema_history_v2 FROM PUBLIC;
DO $$
DECLARE client_role text;
BEGIN
    FOREACH client_role IN ARRAY ARRAY['anon','authenticated'] LOOP
        IF to_regrole(client_role) IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON TABLE taskpilot_migrations.flyway_schema_history_v2 FROM %I',client_role);
        END IF;
    END LOOP;
END $$;
-- Do not ALTER the active history table here: Flyway holds it on another
-- connection during migrate. ACL revocation above denies client access.
