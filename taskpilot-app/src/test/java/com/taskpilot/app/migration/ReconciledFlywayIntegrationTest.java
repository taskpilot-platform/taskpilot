package com.taskpilot.app.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
        return Flyway.configure().dataSource(url, "test", "test").locations("classpath:db/reconciled")
                .table(history).baselineVersion("41").baselineOnMigrate(false).validateOnMigrate(true)
                .outOfOrder(false).cleanDisabled(true).failOnMissingLocations(true).load();
    }

    private String scalar(Connection c, String sql) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }

    private void legacy(String url) {
        Flyway.configure().dataSource(url, "test", "test").locations("classpath:db/reconciled")
                .table("flyway_schema_history").target("41").load().migrate();
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
            assertThat(scalar(c, "SELECT has_table_privilege('anon','public.flyway_schema_history_v2','SELECT')")).isEqualTo("f");
            assertThat(scalar(c, "SELECT has_table_privilege('authenticated','public.flyway_schema_history_v2','INSERT')")).isEqualTo("f");
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
        var wrong = new RecoveryAdoption.Approval("wrong_database", approved.username(), approved.jdbcUrlSha256(),
                approved.catalogDigest(), approved.legacyHistoryDigest(), approved.dataDigest(), approved.bundleSha256());
        assertThatThrownBy(() -> RecoveryAdoption.adopt(target, wrong)).isInstanceOf(SecurityException.class);
        try (var c = DriverManager.getConnection(url, "test", "test"); var s = c.createStatement()) {
            assertThat(scalar(c, "SELECT to_regclass('public.flyway_schema_history_v2') IS NULL")).isEqualTo("t");
            s.execute("ALTER TABLE users ADD COLUMN unreviewed_drift text");
        }
        assertThatThrownBy(() -> RecoveryAdoption.adopt(target, approved)).isInstanceOf(SecurityException.class);
        try (var c = DriverManager.getConnection(url, "test", "test")) {
            assertThat(scalar(c, "SELECT to_regclass('public.flyway_schema_history_v2') IS NULL")).isEqualTo("t");
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
}
