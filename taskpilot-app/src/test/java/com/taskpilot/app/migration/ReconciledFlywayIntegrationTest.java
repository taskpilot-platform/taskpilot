package com.taskpilot.app.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ReconciledFlywayIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("recovery_tests").withUsername("test").withPassword("test");
    @Container
    static PostgreSQLContainer<?> stock = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("stock_recovery").withUsername("test").withPassword("test");

    @BeforeAll
    static void roles() throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test"); var s = c.createStatement()) {
            s.execute("CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS");
        }
    }

    private String database(String name) throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test"); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
    }

    private Flyway flyway(String url, String history) {
        return MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url,"test","test")).table(history).load();
    }

    private String scalar(Connection c, String sql) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }

    private void legacy(String url) {
        Flyway.configure().dataSource(url, "test", "test").locations("classpath:db/reconciled")
                .table("flyway_schema_history").target("41").skipDefaultCallbacks(true).load().migrate();
    }

    @Test
    void freshDatabaseContainsFullSchemaSettingsAndProtectedHistory() throws Exception {
        String url = database("recovery_fresh");
        try (var c = DriverManager.getConnection(url, "test", "test"); var s = c.createStatement()) {
            s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon, authenticated");
        }
        var f = flyway(url, "flyway_schema_history_v2");
        assertThat(f.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(f.validateWithResult().validationSuccessful).isTrue();
        assertThat(f.migrate().migrationsExecuted).isZero();
        assertThatThrownBy(f::clean).isInstanceOf(FlywayException.class);
        try (var c = DriverManager.getConnection(url, "test", "test")) {
            assertThat(scalar(c, "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'team_chat_%'")).isEqualTo("4");
            assertThat(scalar(c, "SELECT count(*) FROM system_settings WHERE key_name='heuristic.weights'")).isEqualTo("1");
            assertThat(scalar(c, "SELECT has_table_privilege('anon','taskpilot_migrations.flyway_schema_history_v2','SELECT')")).isEqualTo("f");
            assertThat(scalar(c, "SELECT has_table_privilege('authenticated','taskpilot_migrations.flyway_schema_history_v2','INSERT')")).isEqualTo("f");
        }
    }

    @Test
    void explicitAdoptionPreservesLegacyHistoryAndExistingData() throws Exception {
        String url = database("recovery_existing"); legacy(url);
        String digest;
        try (var c = DriverManager.getConnection(url, "test", "test"); var s = c.createStatement()) {
            s.execute("UPDATE flyway_schema_history SET checksum=123,description='Legacy incident evidence'; "
                    + "INSERT INTO users(email,full_name,password_hash) VALUES('fixture@example.invalid','Preserved','hash'); "
                    + "INSERT INTO system_settings(key_name,value_json) VALUES('custom.setting','{\"retained\":true}')");
            digest = scalar(c, "SELECT md5(string_agg(row_to_json(h)::text,'|' ORDER BY installed_rank)) FROM flyway_schema_history h");
        }
        var f = flyway(url, "flyway_schema_history_v2");
        assertThatThrownBy(f::migrate).isInstanceOf(FlywayException.class);
        f.baseline();
        assertThat(f.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(f.validateWithResult().validationSuccessful).isTrue();
        assertThat(f.migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url, "test", "test")) {
            assertThat(scalar(c, "SELECT md5(string_agg(row_to_json(h)::text,'|' ORDER BY installed_rank)) FROM flyway_schema_history h")).isEqualTo(digest);
            assertThat(scalar(c, "SELECT full_name||'|'||password_hash FROM users WHERE email='fixture@example.invalid'")).isEqualTo("Preserved|hash");
            assertThat(scalar(c, "SELECT value_json->>'retained' FROM system_settings WHERE key_name='custom.setting'")).isEqualTo("true");
        }
    }

    @Test
    void sameNamedWrongIndexIsRejectedInsteadOfSkipped() throws Exception {
        String url = database("recovery_wrong_index"); legacy(url);
        try (var c = DriverManager.getConnection(url, "test", "test"); var s = c.createStatement()) {
            s.execute("CREATE INDEX idx_project_meetings_scheduled_start ON project_meetings(title)");
        }
        var f = flyway(url, "flyway_schema_history_v2"); f.baseline();
        assertThatThrownBy(f::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining("schedule index definition");
    }

    @Test
    void existingWrongScheduleTypeIsRejectedInsteadOfSkipped() throws Exception {
        String url = database("recovery_wrong_type"); legacy(url);
        try (var c = DriverManager.getConnection(url, "test", "test"); var s = c.createStatement()) {
            s.execute("ALTER TABLE project_meetings ALTER COLUMN scheduled_start_time TYPE timestamp without time zone");
        }
        var f = flyway(url, "flyway_schema_history_v2"); f.baseline();
        assertThatThrownBy(f::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining("schedule column type");
    }

    @Test
    void operatorRejectsWrongTargetAndChangedSchemaBeforeWritingHistory() throws Exception {
        String url = database("recovery_operator"); legacy(url);
        var target = new RecoveryAdoption.Target(url, "test", "test");
        var approved = RecoveryAdoption.inspect(target).approval();
        var wrong = new RecoveryAdoption.Approval("wrong_database", approved.username(), approved.loginUsername(), approved.jdbcUrlSha256(),
                approved.catalogDigest(), approved.legacyHistoryDigest(), approved.dataDigest(), approved.bundleSha256());
        assertThatThrownBy(() -> RecoveryAdoption.adopt(target, wrong)).isInstanceOf(SecurityException.class);
        try (var c = DriverManager.getConnection(url, "test", "test"); var s = c.createStatement()) {
            assertThat(scalar(c, "SELECT to_regclass('taskpilot_migrations.flyway_schema_history_v2') IS NULL")).isEqualTo("t");
            s.execute("ALTER TABLE users ADD COLUMN unreviewed_drift text");
        }
        assertThatThrownBy(() -> RecoveryAdoption.adopt(target, approved)).isInstanceOf(SecurityException.class);
        try (var c = DriverManager.getConnection(url, "test", "test")) {
            assertThat(scalar(c, "SELECT to_regclass('taskpilot_migrations.flyway_schema_history_v2') IS NULL")).isEqualTo("t");
        }
        String validUrl = database("recovery_operator_valid"); legacy(validUrl);
        var valid = new RecoveryAdoption.Target(validUrl, "test", "test");
        var before = RecoveryAdoption.inspect(valid).approval();
        RecoveryAdoption.adopt(valid, before);
        var after = RecoveryAdoption.inspect(valid).approval();
        assertThat(after.legacyHistoryDigest()).isEqualTo(before.legacyHistoryDigest());
        assertThat(after.dataDigest()).isEqualTo(before.dataDigest());
        assertThat(flyway(validUrl, "flyway_schema_history_v2").validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void freshStockPostgresDoesNotRequireSupabaseRoles() {
        assertThat(flyway(stock.getJdbcUrl(), "flyway_schema_history_v2").migrate().migrationsExecuted).isEqualTo(2);
    }

    @Test
    void freshSupabaseDefaultsCannotExposeIdentityMappings() throws Exception {
        String url = database("recovery_client_defaults");
        try (var c = DriverManager.getConnection(url,"test","test"); var s=c.createStatement()) {
            s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon,authenticated");
        }
        flyway(url,"flyway_schema_history_v2").migrate();
        try (var c=DriverManager.getConnection(url,"test","test")) {
            assertThat(scalar(c,"SELECT has_table_privilege('anon','public.user_identities','UPDATE')")).isEqualTo("f");
            assertThat(scalar(c,"SELECT has_table_privilege('authenticated','public.project_meetings','SELECT')")).isEqualTo("f");
        }
    }

    @Test
    void committedBaselineIsPrivateEvenIfForwardMigrationFails() throws Exception {
        String url=database("recovery_partial"); legacy(url);
        try (var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            s.execute("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO anon,authenticated; CREATE INDEX idx_project_meetings_scheduled_start ON project_meetings(title)");
        }
        var f=flyway(url,"flyway_schema_history_v2"); f.baseline();
        assertThatThrownBy(f::migrate).isInstanceOf(FlywayException.class);
        try (var c=DriverManager.getConnection(url,"test","test")) {
            assertThat(scalar(c,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relname='flyway_schema_history_v2' AND has_schema_privilege('anon',n.oid,'USAGE') AND has_table_privilege('anon',c.oid,'UPDATE')")).isEqualTo("0");
        }
    }

    @Test
    void wrongColumnNullabilityOrDefaultIsNotAccepted() throws Exception {
        String url=database("recovery_wrong_column"); legacy(url);
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            s.execute("ALTER TABLE project_meetings ALTER COLUMN scheduled_start_time SET NOT NULL, ALTER COLUMN scheduled_start_time SET DEFAULT CURRENT_TIMESTAMP");
        }
        var f=flyway(url,"flyway_schema_history_v2");f.baseline();
        assertThatThrownBy(f::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining("schedule column");
    }

    @Test
    void wrongIndexOrderingIsNotAccepted() throws Exception {
        String url=database("recovery_wrong_order"); legacy(url);
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            s.execute("CREATE INDEX idx_project_meetings_scheduled_start ON project_meetings(scheduled_start_time DESC NULLS LAST)");
        }
        var f=flyway(url,"flyway_schema_history_v2");f.baseline();
        assertThatThrownBy(f::migrate).isInstanceOf(FlywayException.class).hasStackTraceContaining("schedule index definition");
    }

    @Test
    void validateCliRejectsBaselineMarkerAt42WithoutSqlMigration() throws Exception {
        String url=database("recovery_false_42");legacy(url);
        var config=flyway(url,"flyway_schema_history_v2").getConfiguration();
        Flyway.configure().configuration(config).baselineVersion("42").load().baseline();
        var arguments=java.nio.file.Files.createTempFile("recovery-cli-", ".args");
        java.nio.file.Files.writeString(arguments,"-cp\n\""+System.getProperty("java.class.path").replace('\\','/')+"\"\n"+RecoveryAdoption.class.getName()+"\n--validate\n");
        var process=new ProcessBuilder(PathToJava(),"--enable-preview","@"+arguments);
        process.environment().putAll(java.util.Map.of("DB_URL",url,"DB_USERNAME","test","DB_PASSWORD","test"));
        var child=process.redirectErrorStream(true).start();var output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(child.waitFor()).withFailMessage(output).isNotZero();
    }

    private String PathToJava() { return java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(); }

    @Test
    void normalForwardMigrationDoesNotRequireChangingJavaRecoveryPolicy() throws Exception {
        String url=database("recovery_forward");var initial=flyway(url,"flyway_schema_history_v2");initial.migrate();
        var directory=java.nio.file.Files.createTempDirectory("reviewed-forward-");
        java.nio.file.Files.writeString(directory.resolve("V43__future_schema_change.sql"),"CREATE TABLE public.future_migration_probe(id integer);");
        var next=MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url,"test","test"))
                .locations("classpath:db/reconciled","filesystem:"+directory.toAbsolutePath()).load();
        RecoveryAdoption.verifyBeforeMigrate(next);
        assertThat(next.migrate().migrationsExecuted).isEqualTo(1);
        RecoveryAdoption.validateComplete(next);
    }

    @ParameterizedTest
    @ValueSource(strings={
            "CREATE TABLE metadata_business_probe(id bigint PRIMARY KEY);",
            "CREATE TYPE metadata_business_state AS ENUM ('NEW');",
            "CREATE SEQUENCE metadata_business_sequence;"
    })
    void unqualifiedForwardObjectsCannotPassCompleteValidation(String sql) throws Exception {
        String url=database("metadata_guard_"+java.util.UUID.randomUUID().toString().replace("-",""));
        var initial=flyway(url,"flyway_schema_history_v2");initial.migrate();
        // Actual Flyway table, primary/success indexes, row and array types are allowed.
        RecoveryAdoption.validateComplete(initial);
        var directory=java.nio.file.Files.createTempDirectory("metadata-negative-");
        java.nio.file.Files.writeString(directory.resolve("V43__metadata_negative_probe.sql"),sql);
        var next=MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url,"test","test"))
                .locations("classpath:db/reconciled","filesystem:"+directory.toAbsolutePath()).load();
        assertThat(next.migrate().migrationsExecuted).isEqualTo(1);
        assertThatThrownBy(() -> RecoveryAdoption.validateComplete(next))
                .isInstanceOf(SecurityException.class).hasMessageContaining("UNEXPECTED_MIGRATION_METADATA_OBJECT");
    }

    @Test
    void metadataObjectsAreRejectedEvenBeforeHistoryExists() throws Exception {
        String url=database("metadata_before_history");
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            s.execute("CREATE SCHEMA taskpilot_migrations; CREATE TABLE taskpilot_migrations.flyway_schema_history_v2_s_idx(id bigint)");
        }
        assertThatThrownBy(() -> RecoveryAdoption.verifyBeforeMigrate(flyway(url,"flyway_schema_history_v2")))
                .isInstanceOf(SecurityException.class).hasMessageContaining("UNEXPECTED_MIGRATION_METADATA_OBJECT");
    }

    @Test
    void approvedBaseline41CanResumeAndUnreviewedCallbacksNeverExecute() throws Exception {
        String url=database("recovery_resume");legacy(url);
        var target=new RecoveryAdoption.Target(url,"test","test");
        var approved=RecoveryAdoption.inspect(target).approval();
        flyway(url,"flyway_schema_history_v2").baseline();
        RecoveryAdoption.adopt(target,approved);
        RecoveryAdoption.validateComplete(flyway(url,"flyway_schema_history_v2"));
        try(var c=DriverManager.getConnection(url,"test","test")) {
            assertThat(scalar(c,"SELECT to_regclass('public.unreviewed_callback_executed') IS NULL")).isEqualTo("t");
        }
    }

    @Test
    void writeFreezeAllowsReadsAndReleasesWaitingWriterAfterVerification() throws Exception {
        String url=database("recovery_write_freeze");legacy(url);
        try(var lock=DriverManager.getConnection(url,"test","test");var writer=DriverManager.getConnection(url,"test","test");
            var observer=DriverManager.getConnection(url,"test","test");var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);RecoveryAdoption.freezeWrites(lock);
            int writerPid=Integer.parseInt(scalar(writer,"SELECT pg_backend_pid()"));
            var pending=executor.submit(() -> {try(var s=writer.createStatement()) {return s.executeUpdate("INSERT INTO users(email,full_name) VALUES('waiting@example.invalid','Synthetic writer')");}});
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean waiting=false;
            while(System.nanoTime()<deadline && !pending.isDone()) {
                waiting="Lock".equals(scalar(observer,"SELECT coalesce(wait_event_type,'') FROM pg_stat_activity WHERE pid="+writerPid));
                if(waiting)break;
                Thread.sleep(10);
            }
            assertThat(waiting).isTrue();
            assertThat(scalar(observer,"SELECT count(*) FROM users")).isEqualTo("0");
            lock.rollback();
            assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE UNIQUE INDEX idx_users_email_lower_trimmed ON public.users(id)",
            "CREATE UNIQUE INDEX idx_users_email_lower_trimmed ON public.users(lower(trim(email))) WHERE id > 100",
            "CREATE UNIQUE INDEX idx_users_email_lower_trimmed ON public.projects(id)"
    })
    void invalidEmailUniquenessCannotBeApprovedOrWriteNewHistory(String definition) throws Exception {
        String url=database("recovery_bad_email_"+java.util.UUID.randomUUID().toString().replace("-",""));legacy(url);
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            s.execute("DROP INDEX public.idx_users_email_lower_trimmed; "+definition);
        }
        var target=new RecoveryAdoption.Target(url,"test","test");
        var observed=RecoveryAdoption.inspect(target);
        assertThat(observed.emailUniqueValid()).isFalse();
        assertThatThrownBy(() -> RecoveryAdoption.adopt(target,observed.approval()))
                .isInstanceOf(SecurityException.class).hasMessageContaining("UNSUPPORTED_SOURCE_STATE");
        assertNoNewHistory(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE public.users DROP COLUMN avatar_url",
            "ALTER TABLE public.users ALTER COLUMN avatar_url TYPE varchar(10)",
            "ALTER TABLE public.users ALTER COLUMN avatar_url SET NOT NULL",
            "ALTER TABLE public.users DROP CONSTRAINT users_email_key"
    })
    void reinspectionCannotApproveAnIncompatibleApplicationSchema(String drift) throws Exception {
        String url=database("recovery_bad_schema_"+java.util.UUID.randomUUID().toString().replace("-",""));legacy(url);
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) { s.execute(drift); }
        var target=new RecoveryAdoption.Target(url,"test","test");
        var approved=RecoveryAdoption.inspect(target).approval();
        assertThatThrownBy(() -> RecoveryAdoption.adopt(target,approved))
                .isInstanceOf(SecurityException.class).hasMessageContaining("REVIEWED_SCHEMA_MISMATCH");
        assertNoNewHistory(url);
    }

    @Test
    void completeValidationRejectsSchemaDriftEvenWhenMigrationChecksumsMatch() throws Exception {
        String url=database("recovery_completed_schema_drift");
        var f=flyway(url,"flyway_schema_history_v2");f.migrate();
        RecoveryAdoption.validateComplete(f);
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            s.execute("ALTER TABLE public.users DROP COLUMN avatar_url");
        }
        assertThatThrownBy(() -> RecoveryAdoption.validateComplete(f))
                .isInstanceOf(SecurityException.class).hasMessageContaining("REVIEWED_SCHEMA_MISMATCH");
    }

    @Test
    void completeValidationRequiresTheV42IndexToStillExist() throws Exception {
        String url=database("recovery_completed_index_drift");
        var f=flyway(url,"flyway_schema_history_v2");f.migrate();
        RecoveryAdoption.validateComplete(f);
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            s.execute("DROP INDEX public.idx_project_meetings_scheduled_start");
        }
        assertThatThrownBy(() -> RecoveryAdoption.validateComplete(f))
                .isInstanceOf(SecurityException.class).hasMessageContaining("REVIEWED_SCHEMA_MISMATCH");
    }

    @Test
    void reviewedContractMatchesASeparateFreshV41Baseline() throws Exception {
        String url=database("recovery_contract_reference");legacy(url);
        try(var c=DriverManager.getConnection(url,"test","test")) {
            c.setReadOnly(true);c.setAutoCommit(false);
            assertThat(ReviewedSchemaContract.digest(c)).isEqualTo(ReviewedSchemaContract.EXPECTED_V41_SHA256);
            c.rollback();
        }
    }

    private void assertNoNewHistory(String url) throws Exception {
        try(var c=DriverManager.getConnection(url,"test","test")) {
            assertThat(scalar(c,"SELECT to_regclass('taskpilot_migrations.flyway_schema_history_v2') IS NULL")).isEqualTo("t");
        }
    }
}
