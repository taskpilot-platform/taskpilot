package com.taskpilot.app.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;

/** Structural contract derived from the reviewed V41 baseline, never from the target being approved. */
final class ReviewedSchemaContract {
    private ReviewedSchemaContract() {}

    // Changes require a reviewed baseline/contract update and a fresh PostgreSQL integration run.
    static final String EXPECTED_V41_SHA256="a74f4cfac91fa1184ac18284fadf558ca6b7bc64e1276c84fdf99c6fa5876da5";
    private static final String SCHEDULE_INDEX_DEFINITION=
            "CREATE INDEX idx_project_meetings_scheduled_start ON public.project_meetings USING btree (scheduled_start_time)";
    // PostgreSQL 17 patch versions deparse this reviewed array cast differently
    // before/after pg_dump restore. Accept only these two complete definitions.
    private static final String SOURCE_QUEUE_INDEX=
            "CREATE INDEX idx_documents_queue_claim ON public.documents USING btree (status, next_attempt_at, lease_until, created_at) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RETRY_WAIT'::character varying, 'PROCESSING'::character varying])::text[]))";
    private static final String RESTORED_QUEUE_INDEX=
            "CREATE INDEX idx_documents_queue_claim ON public.documents USING btree (status, next_attempt_at, lease_until, created_at) WHERE ((status)::text = ANY (ARRAY[('QUEUED'::character varying)::text, ('RETRY_WAIT'::character varying)::text, ('PROCESSING'::character varying)::text]))";
    private static final String STRUCTURE="""
        SELECT jsonb_build_object(
          'tables',(SELECT jsonb_agg(to_jsonb(x) ORDER BY name) FROM
            (SELECT c.relname name,c.relkind kind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='public' AND c.relkind IN ('r','p') AND c.relname NOT LIKE 'flyway%') x),
          'columns',(SELECT jsonb_agg(to_jsonb(x) ORDER BY tbl,name) FROM
            (SELECT c.relname tbl,a.attname name,tn.nspname type_schema,t.typname type_name,
                    a.atttypmod type_modifier,a.attnotnull not_null,a.attidentity identity_kind,
                    a.attgenerated generated_kind,pg_get_expr(d.adbin,d.adrelid) default_expression,
                    cn.nspname collation_schema,co.collname collation_name
             FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
             JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_type t ON t.oid=a.atttypid
             JOIN pg_namespace tn ON tn.oid=t.typnamespace
             LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
             LEFT JOIN pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_namespace cn ON cn.oid=co.collnamespace
             WHERE n.nspname='public' AND c.relkind IN ('r','p') AND c.relname NOT LIKE 'flyway%'
               AND a.attnum>0 AND NOT a.attisdropped) x),
          'constraints',(SELECT jsonb_agg(to_jsonb(x) ORDER BY tbl,name) FROM
            (SELECT c.relname tbl,k.conname name,k.convalidated validated,pg_get_constraintdef(k.oid) definition
             FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='public' AND c.relname NOT LIKE 'flyway%') x),
          'indexes',(SELECT jsonb_agg(to_jsonb(x) ORDER BY tbl,name) FROM
            (SELECT c.relname tbl,idx.relname name,pg_get_indexdef(i.indexrelid) definition,
                    i.indisvalid valid,i.indisready ready
             FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid JOIN pg_namespace n ON n.oid=c.relnamespace
             JOIN pg_class idx ON idx.oid=i.indexrelid
             WHERE n.nspname='public' AND c.relname NOT LIKE 'flyway%'
               AND (idx.relname<>'idx_project_meetings_scheduled_start'
                    OR pg_get_indexdef(i.indexrelid)<>'CREATE INDEX idx_project_meetings_scheduled_start ON public.project_meetings USING btree (scheduled_start_time)'
                    OR NOT i.indisvalid OR NOT i.indisready)) x),
          'enums',(SELECT jsonb_agg(to_jsonb(x) ORDER BY name,sort) FROM
            (SELECT t.typname name,e.enumsortorder sort,e.enumlabel label FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid
             JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public') x),
          'sequences',(SELECT jsonb_agg(to_jsonb(x) ORDER BY sequencename) FROM
            (SELECT sequencename,data_type,start_value,min_value,max_value,increment_by,cycle,cache_size
             FROM pg_sequences WHERE schemaname='public' AND sequencename NOT LIKE 'flyway%') x)
        )::text
        """;

    static String digest(Connection connection) throws Exception {
        if(connection.getAutoCommit()) throw new IllegalStateException("SCHEMA_CHECK_REQUIRES_TRANSACTION");
        // PostgreSQL deparsers must see the same search path on the reference and target.
        try(var s=connection.createStatement()) {
            s.execute("SET LOCAL search_path=pg_catalog,public; SET LOCAL TIME ZONE 'UTC'");
            s.setQueryTimeout(30);
            try(var r=s.executeQuery(STRUCTURE)) {
                if(!r.next()) throw new SecurityException("EMPTY_SCHEMA_CONTRACT");
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(r.getString(1).replace(SOURCE_QUEUE_INDEX, RESTORED_QUEUE_INDEX)
                                .getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    static void verify(Connection connection) throws Exception {
        if(!EXPECTED_V41_SHA256.equals(digest(connection))) throw new SecurityException("REVIEWED_SCHEMA_MISMATCH");
    }

    static void verifyComplete(Connection connection) throws Exception {
        verify(connection);
        try(var s=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_index WHERE indexrelid=to_regclass('public.idx_project_meetings_scheduled_start') AND pg_get_indexdef(indexrelid)=? AND indisvalid AND indisready)")) {
            s.setQueryTimeout(30);s.setString(1,SCHEDULE_INDEX_DEFINITION);
            try(var r=s.executeQuery()) {
                if(!r.next() || !r.getBoolean(1)) throw new SecurityException("REVIEWED_SCHEMA_MISMATCH: V42 index missing");
            }
        }
    }
}
