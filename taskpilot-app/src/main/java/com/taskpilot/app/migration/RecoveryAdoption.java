package com.taskpilot.app.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;

/** Explicit operator CLI. Never registered as a Spring bean or HTTP endpoint. */
public final class RecoveryAdoption {
    public record Target(String jdbcUrl, String username, String password) {
        public Target { jdbcUrl=MigrationDatabaseConfiguration.migrationUrl(jdbcUrl); }
        @Override public String toString() { return "Target[credentials redacted]"; }
    }
    public record Approval(String database, String username, String loginUsername, String jdbcUrlSha256, String catalogDigest,
                           String legacyHistoryDigest, String dataDigest, String bundleSha256) {}
    public record Observation(Approval approval, int historyRows, String historyVersion,
                              boolean historySuccessful, boolean canAlterMeetings, boolean emailUniqueValid,
                              boolean scheduleDefinitionValid) {}

    private static final String HISTORY="taskpilot_migrations.flyway_schema_history_v2";
    private static final String SCHEDULE_COLUMNS="SELECT count(*)=2 FROM pg_attribute WHERE attrelid='public.project_meetings'::regclass AND NOT attisdropped AND attname IN ('scheduled_start_time','scheduled_end_time') AND atttypid='timestamptz'::regtype AND atttypmod IN (-1,6) AND NOT attnotnull AND NOT atthasdef AND attidentity='' AND attgenerated=''";
    private static final String EMAIL_UNIQUENESS="""
        SELECT EXISTS (
          SELECT 1 FROM pg_index i
          WHERE i.indexrelid=to_regclass('public.idx_users_email_lower_trimmed')
            AND i.indrelid='public.users'::regclass
            AND i.indisunique AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
            AND i.indnkeyatts=1 AND i.indnatts=1 AND i.indpred IS NULL
            AND pg_get_indexdef(i.indexrelid)=
              'CREATE UNIQUE INDEX idx_users_email_lower_trimmed ON public.users USING btree (lower(TRIM(BOTH FROM email)))'
        )::text
        """;

    private static final List<String> SCRIPTS = List.of("V41__reviewed_schema_baseline.sql", "V42__reconcile_meeting_schedule.sql");
    private static final String CATALOG = """
        SELECT md5(jsonb_build_object(
          'columns',(SELECT jsonb_agg(to_jsonb(x) ORDER BY table_name,ordinal_position) FROM
            (SELECT table_name,column_name,ordinal_position,data_type,udt_schema,udt_name,
                    character_maximum_length,numeric_precision,numeric_scale,is_nullable,column_default,is_identity,identity_generation
             FROM information_schema.columns WHERE table_schema='public' AND table_name NOT LIKE 'flyway%') x),
          'constraints',(SELECT jsonb_agg(to_jsonb(x) ORDER BY tbl,name) FROM
            (SELECT c.relname tbl,k.conname name,pg_get_constraintdef(k.oid) definition
             FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='public' AND c.relname NOT LIKE 'flyway%') x),
          'indexes',(SELECT jsonb_agg(to_jsonb(x) ORDER BY tablename,indexname) FROM
            (SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename NOT LIKE 'flyway%') x),
          'security',(SELECT jsonb_agg(to_jsonb(x) ORDER BY name) FROM
            (SELECT c.relname name,c.relkind kind,c.relrowsecurity rls,c.relforcerowsecurity forced,
                    pg_get_userbyid(c.relowner) owner,c.relacl::text acl
             FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m') AND c.relname NOT LIKE 'flyway%') x),
          'policies',(SELECT jsonb_agg(to_jsonb(x) ORDER BY tablename,policyname) FROM
            (SELECT * FROM pg_policies WHERE schemaname='public' AND tablename NOT LIKE 'flyway%') x),
          'enums',(SELECT jsonb_agg(to_jsonb(x) ORDER BY name,sort) FROM
            (SELECT t.typname name,e.enumsortorder sort,e.enumlabel label FROM pg_enum e JOIN pg_type t ON t.oid=e.enumtypid
             JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public') x),
          'sequences',(SELECT jsonb_agg(to_jsonb(x) ORDER BY sequencename) FROM
            (SELECT sequencename,data_type,start_value,min_value,max_value,increment_by,cycle,cache_size
             FROM pg_sequences WHERE schemaname='public' AND sequencename NOT LIKE 'flyway%') x),
          'extensions',(SELECT jsonb_agg(to_jsonb(x) ORDER BY name) FROM
            (SELECT e.extname name,e.extversion version,n.nspname schema FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace) x),
          'views',(SELECT jsonb_agg(to_jsonb(x) ORDER BY viewname) FROM
            (SELECT viewname,definition FROM pg_views WHERE schemaname='public') x),
          'routines',(SELECT jsonb_agg(to_jsonb(x) ORDER BY name,args) FROM
            (SELECT p.proname name,pg_get_function_identity_arguments(p.oid) args,p.prosrc body
             FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public'
             AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.objid=p.oid AND d.deptype='e')) x)
        )::text)
        """;

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static String scalar(Connection c, String sql) throws Exception {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(30);
            try (var r = s.executeQuery(sql)) { if (!r.next()) throw new SecurityException("EMPTY_PREFLIGHT_RESULT"); return r.getString(1); }
        }
    }
    private static Flyway flyway(Target t) {
        return MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(t.jdbcUrl(),t.username(),t.password())).load();
    }
    private static String scriptHash(String script) throws Exception {
        try(var in=RecoveryAdoption.class.getResourceAsStream("/db/reconciled/"+script)) {
            if(in==null) throw new SecurityException("MISSING_REVIEWED_BUNDLE");
            return hash(new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n"));
        }
    }
    private static String bundleHash() throws Exception {
        var content = new StringBuilder();
        for (String script : SCRIPTS) {
            try (var in = RecoveryAdoption.class.getResourceAsStream("/db/reconciled/"+script)) {
                if (in == null) throw new SecurityException("MISSING_REVIEWED_BUNDLE");
                content.append(script).append('\n').append(new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n"));
            }
        }
        content.append("reviewed-schema-contract:").append(ReviewedSchemaContract.EXPECTED_V41_SHA256);
        return hash(content.toString());
    }
    private static String dataDigest(Connection c) throws Exception {
        var tables = new java.util.ArrayList<String>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT LIKE 'flyway%' ORDER BY tablename")) {
            while (r.next()) tables.add(r.getString(1));
        }
        var hashes = new StringBuilder();
        for (String table : tables) {
            String identifier = '"'+table.replace("\"","\"\"")+'"';
            hashes.append(table).append(':').append(scalar(c,"SELECT md5(coalesce(string_agg(to_jsonb(t)::text,'|' ORDER BY to_jsonb(t)::text),'')) FROM public."+identifier+" t")).append('\n');
        }
        return hash(hashes.toString());
    }
    public static Observation inspect(Target target) throws Exception {
        try (var c = DriverManager.getConnection(target.jdbcUrl(),target.username(),target.password())) {
            c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try (var s = c.createStatement()) { s.execute("SET LOCAL TIME ZONE 'UTC'; SET LOCAL statement_timeout='30s'"); }
            var approval = new Approval(scalar(c,"SELECT current_database()"),scalar(c,"SELECT current_user"),target.username(),hash(target.jdbcUrl()),
                    scalar(c,CATALOG),scalar(c,"SELECT md5(string_agg(row_to_json(h)::text,'|' ORDER BY installed_rank)) FROM public.flyway_schema_history h"),
                    dataDigest(c),bundleHash());
            var result = new Observation(approval,Integer.parseInt(scalar(c,"SELECT count(*) FROM public.flyway_schema_history")),
                    scalar(c,"SELECT max(version::int)::text FROM public.flyway_schema_history WHERE version ~ '^[0-9]+$'"),
                    Boolean.parseBoolean(scalar(c,"SELECT bool_and(success)::text FROM public.flyway_schema_history")),
                    Boolean.parseBoolean(scalar(c,"SELECT (pg_has_role(current_user,c.relowner,'USAGE') OR r.rolsuper)::text FROM pg_class c CROSS JOIN pg_roles r WHERE c.oid='public.project_meetings'::regclass AND r.rolname=current_user")),
                    Boolean.parseBoolean(scalar(c,EMAIL_UNIQUENESS)),
                    "t".equals(scalar(c,SCHEDULE_COLUMNS)));
            c.rollback(); return result;
        }
    }
    static void verifyTarget(Target target, Approval approved) throws Exception {
        if (approved == null || !hash(target.jdbcUrl()).equals(approved.jdbcUrlSha256()) || !target.username().equals(approved.loginUsername()))
            throw new SecurityException("TARGET_MISMATCH");
    }
    static void freezeWrites(Connection c) throws Exception {
        var tables=new java.util.ArrayList<String>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
            while(r.next()) tables.add("public.\""+r.getString(1).replace("\"","\"\"")+"\"");
        }
        try(var s=c.createStatement()) {
            s.execute("SET LOCAL lock_timeout='3s'; SET LOCAL statement_timeout='30s'; SET LOCAL idle_in_transaction_session_timeout='45s'");
            s.execute("LOCK TABLE "+String.join(",",tables)+" IN SHARE MODE NOWAIT");
        }
    }
    private static void approvedInventory(Flyway f) {
        for(var m:f.info().all()) {
            boolean baseline="41".equals(m.getVersion()==null?null:m.getVersion().getVersion())
                    && m.getScript()!=null && m.getScript().contains("Flyway Baseline");
            if(!baseline && !SCRIPTS.contains(m.getScript())) throw new SecurityException("UNREVIEWED_MIGRATIONS");
        }
    }
    private static void baselineTag(Target target) throws Exception {
        try(var c=DriverManager.getConnection(target.jdbcUrl(),target.username(),target.password());var s=c.createStatement()) {
            s.execute("COMMENT ON TABLE "+HISTORY+" IS 'TaskPilot reviewed baseline SHA-256:"+scriptHash(SCRIPTS.getFirst())+"'");
        }
    }
    private static boolean verifyRows(Flyway f, boolean complete) throws Exception {
        try(var c=f.getConfiguration().getDataSource().getConnection()) {
            if("t".equals(scalar(c,"SELECT to_regclass('"+HISTORY+"') IS NULL"))) {
                if(complete) throw new SecurityException("RECOVERY_HISTORY_MISSING");
                return false;
            }
            var rows=new java.util.ArrayList<String[]>();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT version,type,script,checksum::text,success::text FROM "+HISTORY+" ORDER BY installed_rank")) {
                while(r.next()) rows.add(new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)});
            }
            if(rows.isEmpty() && !complete) return false;
            if(rows.size()<1 || !"41".equals(rows.getFirst()[0]) || !"true".equals(rows.getFirst()[4])) throw new SecurityException("UNEXPECTED_HISTORY_ROWS");
            var first=rows.getFirst();
            if("BASELINE".equals(first[1])) {
                if(first[3]!=null || !scalar(c,"SELECT coalesce(obj_description('"+HISTORY+"'::regclass),'')").equals("TaskPilot reviewed baseline SHA-256:"+scriptHash(SCRIPTS.getFirst())))
                    throw new SecurityException("UNBOUND_BASELINE_SNAPSHOT");
            } else if(!"SQL".equals(first[1]) || !SCRIPTS.getFirst().equals(first[2]) || first[3]==null) throw new SecurityException("INVALID_V41_ROW");
            boolean has42=false;
            for(int i=1;i<rows.size();i++) {
                var row=rows.get(i);
                if(row[0]==null || org.flywaydb.core.api.MigrationVersion.fromVersion(row[0]).compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("42"))<0
                        || !"SQL".equals(row[1]) || row[3]==null || !"true".equals(row[4])) throw new SecurityException("INVALID_FORWARD_MIGRATION_ROW");
                if("42".equals(row[0])) {
                    if(!SCRIPTS.getLast().equals(row[2])) throw new SecurityException("V42_SQL_NOT_APPLIED");
                    has42=true;
                }
            }
            if(complete && !has42) throw new SecurityException("V42_SQL_NOT_APPLIED");
            for(String client:List.of("anon","authenticated")) {
                if(!"t".equals(scalar(c,"SELECT to_regrole('"+client+"') IS NULL"))) {
                    if("t".equals(scalar(c,"SELECT has_schema_privilege('"+client+"','taskpilot_migrations','USAGE')"))) throw new SecurityException("CLIENT_CAN_ACCESS_HISTORY_SCHEMA");
                    if(complete && "t".equals(scalar(c,"SELECT has_table_privilege('"+client+"','"+HISTORY+"','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"))) throw new SecurityException("CLIENT_CAN_ACCESS_HISTORY_TABLE");
                }
            }
            return true;
        }
    }
    public static void verifyBeforeMigrate(Flyway f) throws Exception {
        if(verifyRows(f,false) && isRecoveryVersion(f)) verifyApplicationSchema(f,false);
    }
    public static void validateComplete(Flyway f) throws Exception {
        verifyRows(f,true); f.validate();
        if(f.info().pending().length!=0) throw new SecurityException("RECOVERY_HAS_PENDING_MIGRATIONS");
        if(isRecoveryVersion(f)) verifyApplicationSchema(f,true);
    }
    private static boolean isRecoveryVersion(Flyway f) {
        var current=f.info().current();
        return current==null || current.getVersion().compareTo(org.flywaydb.core.api.MigrationVersion.fromVersion("42"))<=0;
    }
    private static void verifyApplicationSchema(Flyway f, boolean complete) throws Exception {
        try(var c=f.getConfiguration().getDataSource().getConnection()) {
            c.setReadOnly(true);c.setAutoCommit(false);
            if(complete) ReviewedSchemaContract.verifyComplete(c);
            else ReviewedSchemaContract.verify(c);
            c.rollback();
        }
    }
    public static void adopt(Target target, Approval approved) throws Exception {
        verifyTarget(target,approved);
        try (var lock = DriverManager.getConnection(target.jdbcUrl(),target.username(),target.password())) {
            lock.setAutoCommit(false);
            if (!"t".equals(scalar(lock,"SELECT pg_try_advisory_xact_lock(84170042)"))) throw new SecurityException("ANOTHER_RECOVERY_OPERATOR_ACTIVE");
            freezeWrites(lock); long started=System.nanoTime();
            var before = inspect(target);
            if (!before.approval().equals(approved)) throw new SecurityException("PREFLIGHT_MISMATCH");
            if (before.historyRows()<1 || !"41".equals(before.historyVersion()) || !before.historySuccessful() || !before.canAlterMeetings() || !before.emailUniqueValid() || !before.scheduleDefinitionValid())
                throw new SecurityException("UNSUPPORTED_SOURCE_STATE");
            ReviewedSchemaContract.verify(lock);
            var f = flyway(target);
            approvedInventory(f);
            if("t".equals(scalar(lock,"SELECT to_regclass('"+HISTORY+"') IS NULL"))) f.baseline();
            else {
                try(var c=f.getConfiguration().getDataSource().getConnection()) {
                    if(!"t".equals(scalar(c,"SELECT count(*)=1 AND bool_and(version='41' AND type='BASELINE' AND checksum IS NULL AND success) FROM "+HISTORY)))
                        throw new SecurityException("V2_HISTORY_ALREADY_PRESENT_USE_VALIDATE");
                }
            }
            baselineTag(target); verifyBeforeMigrate(f);
            if(f.migrate().migrationsExecuted!=1) throw new SecurityException("UNEXPECTED_MIGRATION_COUNT");
            validateComplete(f);
            var after = inspect(target);
            if (!after.approval().legacyHistoryDigest().equals(approved.legacyHistoryDigest()) || !after.approval().dataDigest().equals(approved.dataDigest()))
                throw new SecurityException("DATA_OR_LEGACY_HISTORY_CHANGED_REVIEW_REQUIRED");
            scalar(lock,"SELECT 1"); // Prove the write-freeze transaction did not expire.
            lock.rollback();
            System.out.println("RECOVERY_WRITE_FREEZE_MILLIS="+((System.nanoTime()-started)/1_000_000));
        }
    }
    public static void main(String[] args) {
        try {
            if (args.length!=1 || !List.of("--inspect","--adopt","--validate").contains(args[0])) throw new SecurityException("USE_INSPECT_ADOPT_OR_VALIDATE");
            var env = System.getenv();
            var target = new Target(java.util.Objects.requireNonNull(env.get("DB_URL")),java.util.Objects.requireNonNull(env.get("DB_USERNAME")),java.util.Objects.requireNonNull(env.get("DB_PASSWORD")));
            var json = new ObjectMapper();
            if (args[0].equals("--inspect")) System.out.println(json.writeValueAsString(inspect(target)));
            else if (args[0].equals("--adopt")) {
                var approved = json.readValue(Files.readString(Path.of(java.util.Objects.requireNonNull(env.get("TASKPILOT_RECOVERY_APPROVAL")))),Approval.class);
                adopt(target,approved); System.out.println("RECOVERY_ADOPTED_AND_VALIDATED=42;DATA_PRESERVED=true;LEGACY_HISTORY_PRESERVED=true");
            } else {
                var f = flyway(target); validateComplete(f);
                System.out.println("RECOVERY_VALID=true;CURRENT_VERSION="+f.info().current().getVersion().getVersion()+";PENDING=0");
            }
        } catch (SecurityException e) { System.err.println("Recovery refused: "+e.getMessage()); System.exit(2); }
        catch (Exception e) { System.err.println("Recovery failed: "+e.getClass().getSimpleName()+"; inspect private operator logs before retrying"); System.exit(2); }
    }
}
