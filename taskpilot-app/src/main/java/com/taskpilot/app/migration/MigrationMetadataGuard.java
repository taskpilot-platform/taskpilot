package com.taskpilot.app.migration;

import org.flywaydb.core.Flyway;

/** Allow only Flyway 11 PostgreSQL history objects in the private namespace. */
final class MigrationMetadataGuard {
    private MigrationMetadataGuard() {}

    private static final String UNEXPECTED_OBJECTS="""
        WITH history AS (
          SELECT to_regclass('taskpilot_migrations.flyway_schema_history_v2') oid
        ), objects AS (
          SELECT DISTINCT d.classid,d.objid,
                 pg_describe_object(d.classid,d.objid,d.objsubid) description
          FROM pg_depend d JOIN pg_namespace n ON n.oid=d.refobjid
          WHERE d.refclassid='pg_namespace'::regclass AND n.nspname='taskpilot_migrations'
        )
        SELECT string_agg(description,', ' ORDER BY description) FROM objects o,history h
        WHERE NOT coalesce((
          (o.classid='pg_class'::regclass AND EXISTS (
            SELECT 1 FROM pg_class c WHERE c.oid=o.objid AND (
              (c.oid=h.oid AND c.relkind='r') OR
              (c.relkind='i' AND c.relname IN ('flyway_schema_history_v2_pk','flyway_schema_history_v2_s_idx')
               AND EXISTS(SELECT 1 FROM pg_index i WHERE i.indexrelid=c.oid AND i.indrelid=h.oid))
            ))) OR
          (o.classid='pg_type'::regclass AND EXISTS (
            SELECT 1 FROM pg_type t WHERE t.oid=o.objid AND (
              (t.typtype='c' AND t.typrelid=h.oid) OR
              t.oid IN (SELECT row_type.typarray FROM pg_type row_type WHERE row_type.typrelid=h.oid AND row_type.typtype='c')
            ))) OR
          (o.classid='pg_constraint'::regclass AND EXISTS (
            SELECT 1 FROM pg_constraint k WHERE k.oid=o.objid AND k.conrelid=h.oid
              AND k.contype='p' AND k.conname='flyway_schema_history_v2_pk'
          ))
        ),false)
        """;

    static void verify(Flyway flyway) throws Exception {
        try(var connection=flyway.getConfiguration().getDataSource().getConnection()) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                statement.setQueryTimeout(15);
                try(var result=statement.executeQuery(UNEXPECTED_OBJECTS)) {
                    if(!result.next() || result.getString(1)!=null)
                        throw new SecurityException("UNEXPECTED_MIGRATION_METADATA_OBJECT");
                }
            }
            connection.rollback();
        }
    }
}
