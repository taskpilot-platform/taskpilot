package com.taskpilot.app.migration;

import com.taskpilot.app.TaskPilotApplication;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Mandatory: missing Docker is an ERROR, never a disabled/skipped integration.
@Testcontainers
class MigrationSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("migration_policy").withUsername("test").withPassword("test");

    @Test
    @Timeout(150)
    void freshPublicMigrationStartsRealBackend() throws Exception {
        exercise("migration_fresh_backend",false);
    }

    @Test
    @Timeout(150)
    void upgradedPublicMigrationStartsRealBackend() throws Exception {
        exercise("migration_upgrade_backend",true);
    }

    @Test
    @Timeout(90)
    void p7UpgradeKeepsLegacyRecordingLinksAndExpiresUnverifiedActiveAttendance() throws Exception {
        String database = "migration_p7_meetings";
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        var v49 = MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url, "test", "test"))
                .target("49").load();
        v49.migrate();

        try (var connection = DriverManager.getConnection(url, "test", "test"); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO public.users(id, email, full_name) VALUES (7, 'fixture@example.invalid', 'Fixture')");
            statement.execute("INSERT INTO public.projects(id, name) VALUES (2, 'Fixture project')");
            statement.execute("""
                    INSERT INTO public.project_files(id, project_id, uploader_id, file_name, original_name, file_size, storage_key)
                    VALUES (9, 2, 7, 'recording.webm', 'recording.webm', 1024, 'fixture-key')
                    """);
            statement.execute("""
                    INSERT INTO public.project_meetings(id, project_id, host_id, title, room_name, status, recording_file_id, ended_at)
                    VALUES (4, 2, 7, 'Recorded', 'tp-proj-2-fixture', 'ENDED', 9, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO public.project_meeting_participants(id, meeting_id, user_id, role)
                    VALUES (1, 4, 7, 'HOST'), (2, 4, 7, 'HOST')
                    """);
        }

        var latest = MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url, "test", "test")).load();
        assertThat(latest.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        try (var connection = DriverManager.getConnection(url, "test", "test"); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT recording_file_id, room_cleanup_at FROM public.project_meetings WHERE id = 4
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("recording_file_id")).isEqualTo(9);
                assertThat(result.getTimestamp("room_cleanup_at")).isNotNull();
            }
            try (var result = statement.executeQuery("""
                    SELECT count(*) AS rows, count(left_at) AS closed FROM public.project_meeting_participants WHERE meeting_id = 4
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt("rows")).isEqualTo(2);
                assertThat(result.getInt("closed")).isEqualTo(2);
            }
        }
    }

    @Test
    @Timeout(90)
    void p8UpgradePreservesLegacyAdaptiveRowsAndCreatesReviewedOutcomeV2() throws Exception {
        String database = "migration_p8_adaptive";
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        var v50 = MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url, "test", "test"))
                .target("50").load();
        v50.migrate();

        try (var connection = DriverManager.getConnection(url, "test", "test"); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO public.users(id, email, full_name) VALUES (10, 'mgr@example.invalid', 'Manager'), (11, 'dev@example.invalid', 'Dev')");
            statement.execute("INSERT INTO public.projects(id, name) VALUES (20, 'Adaptive Legacy Project')");
            statement.execute("INSERT INTO public.tasks(id, project_id, title, assignee_id) VALUES (30, 20, 'Legacy Task', 11)");
            statement.execute("""
                    INSERT INTO public.recommendation_snapshots(
                        snapshot_id, requested_by_user_id, project_id, task_id, created_at, heuristic_mode,
                        presentation_contract_version, scoring_model_version, differentiation_status,
                        required_skills, recommended_candidate_id, candidate_count, request_source,
                        fit_weight, load_weight, performance_weight, normalization_contract
                    ) VALUES (
                        'legacy-snap-1', 10, 20, 30, CURRENT_TIMESTAMP, 'BALANCED', 'allowlisted-view-v3',
                        'project-ranking-v1', 'DIFFERENTIATED', '["Java"]', 11, 1, 'REST',
                        0.4, 0.3, 0.3, 'normalize-v2-fixed-scale'
                    )
                    """);
            statement.execute("""
                    INSERT INTO public.recommendation_snapshot_candidates(
                        snapshot_id, candidate_id, rank, ranking_key, full_precision_score,
                        ranking_raw_fit, presentation_fit_value, fit_status, stored_workload_value,
                        workload_status, derived_performance_input, performance_status,
                        member_status, selected_as_recommendation
                    ) VALUES (
                        'legacy-snap-1', 11, 1, 1, 0.85, 0.9, 90.0, 'MEASURED', 1,
                        'MEASURED', 0.8, 'MEASURED', 'ACTIVE', true
                    )
                    """);
            statement.execute("""
                    INSERT INTO public.recommendation_decision_events(
                        decision_id, snapshot_id, project_id, task_id, decided_by_user_id, decision_source,
                        decision_type, recommended_candidate_id, selected_candidate_id, created_at
                    ) VALUES ('legacy-dec-1', 'legacy-snap-1', 20, 30, 10, 'REST', 'ACCEPTED', 11, 11, CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO public.recommendation_task_outcomes(
                        outcome_id, snapshot_id, decision_id, project_id, task_id, recommended_candidate_id,
                        selected_candidate_id, decision_type, decision_source, observed_assignee_id,
                        task_status, outcome_type, observed_at, outcome_version
                    ) VALUES (
                        'legacy-out-1', 'legacy-snap-1', 'legacy-dec-1', 20, 30, 11, 11, 'ACCEPTED', 'REST', 11,
                        'DONE', 'ON_TIME', CURRENT_TIMESTAMP, 'outcome-v1'
                    )
                    """);
        }

        var latest = MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url, "test", "test")).load();
        assertThat(latest.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();

        try (var connection = DriverManager.getConnection(url, "test", "test"); var statement = connection.createStatement()) {
            try (var r = statement.executeQuery("SELECT weight_version, synthetic FROM public.recommendation_snapshots WHERE snapshot_id = 'legacy-snap-1'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBoolean("synthetic")).isFalse();
            }
            try (var r = statement.executeQuery("SELECT count(*) FROM public.recommendation_task_outcomes WHERE outcome_id = 'legacy-out-1'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getInt(1)).isEqualTo(1);
            }
            for (String checkSql : List.of(
                    "SELECT to_regclass('public.adaptive_reviewed_outcomes') IS NOT NULL",
                    "SELECT to_regclass('public.adaptive_outcome_invalidations') IS NOT NULL",
                    "SELECT relrowsecurity FROM pg_class WHERE relname = 'adaptive_reviewed_outcomes'",
                    "SELECT relrowsecurity FROM pg_class WHERE relname = 'adaptive_outcome_invalidations'"
            )) {
                try (var r = statement.executeQuery(checkSql)) {
                    assertThat(r.next()).isTrue();
                    assertThat(r.getBoolean(1)).isTrue();
                }
            }
        }
    }

    private void exercise(String database,boolean upgrade) throws Exception {
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),"test","test");var s=c.createStatement()) {
            s.execute("CREATE DATABASE "+database);
        }
        String url="jdbc:postgresql://"+postgres.getHost()+":"+postgres.getMappedPort(5432)+"/"+database;
        var initial=MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url,"test","test")).load();
        int currentBundleVersion=java.util.Arrays.stream(initial.info().all())
                .mapToInt(m -> Integer.parseInt(m.getVersion().getVersion())).max().orElseThrow();
        if(upgrade) {
            new MigrationDatabaseConfiguration().verifiedMigrationStrategy().migrate(initial);
            try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
                s.execute("INSERT INTO public.users(email,full_name) VALUES('preserved@example.invalid','Synthetic preserved fixture')");
            }
        }
        var fixture=Files.createTempDirectory("public-migration-fixture-");
        Files.writeString(fixture.resolve("V"+(currentBundleVersion+1)+"__public_schema_probe.sql"),"""
            CREATE TYPE public.migration_schema_probe_state AS ENUM ('NEW','DONE');
            CREATE SEQUENCE public.migration_schema_probe_sequence;
            CREATE TABLE public.migration_schema_probe(
              id bigint PRIMARY KEY,title text,state public.migration_schema_probe_state DEFAULT 'NEW'
            );
            CREATE INDEX idx_migration_schema_probe_title ON public.migration_schema_probe(title);
            INSERT INTO public.migration_schema_probe(id,title) VALUES(1,'PostgreSQL fixture');
            """);
        var extended=MigrationDatabaseConfiguration.configure(Flyway.configure().dataSource(url,"test","test"))
                .locations("classpath:db/reconciled","filesystem:"+fixture.toAbsolutePath()).load();
        new MigrationDatabaseConfiguration().verifiedMigrationStrategy().migrate(extended);
        assertThat(extended.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,"test","test");var s=c.createStatement()) {
            for(String sql:List.of(
                    "SELECT to_regclass('public.migration_schema_probe') IS NOT NULL",
                    "SELECT to_regclass('public.migration_schema_probe_sequence') IS NOT NULL",
                    "SELECT to_regtype('public.migration_schema_probe_state') IS NOT NULL",
                    "SELECT to_regclass('taskpilot_migrations.migration_schema_probe') IS NULL")) {
                try(var r=s.executeQuery(sql)) { r.next();assertThat(r.getBoolean(1)).isTrue(); }
            }
            if(upgrade) try(var r=s.executeQuery("SELECT count(*) FROM public.users WHERE email='preserved@example.invalid'")) {
                r.next();assertThat(r.getInt(1)).isEqualTo(1);
            }
        }
        // Real application entry configuration, but NOT main(): never load the source .env.
        var properties=new ArrayList<>(List.of(
                "--spring.profiles.active=dev","--server.port=0","--server.address=127.0.0.1",
                "--spring.datasource.url="+url,"--spring.datasource.username=test","--spring.datasource.password=test",
                "--spring.flyway.url="+url,"--spring.flyway.user=test","--spring.flyway.password=test",
                "--spring.jpa.hibernate.ddl-auto=validate","--spring.output.ansi.enabled=never",
                "--ai.gemini.api-key=local-dummy-not-a-secret","--ai.gemini.api-keys=",
                "--ai.groq.enabled=false","--ai.groq.api-key=","--ai.groq.api-keys=",
                "--ai.openrouter.enabled=false","--ai.openrouter.api-key=","--ai.openrouter.api-keys=",
                "--google.drive.client-id=","--google.drive.client-secret=","--google.drive.refresh-token=",
                "--application.security.oauth2.google.client-id=","--application.security.oauth2.google.client-secret=",
                "--application.security.oauth2.github.client-id=","--application.security.oauth2.github.client-secret=",
                "--onesignal.app-id=","--onesignal.api-key=","--livekit.api-key=local-dummy","--livekit.api-secret=local-dummy",
                "--supabase.s3.endpoint=http://127.0.0.1:9","--supabase.s3.access-key=local-dummy","--supabase.s3.secret-key=local-dummy",
                "--application.security.jwt.secret-key=taskpilot-test-only-migration-jwt-signing-key-2026-32bytes",
                "--application.security.jwt.blocklist.provider=memory","--application.security.password-reset.rate-limit.provider=memory",
                "--rag.embedding.poll-interval-ms=86400000","--decorator.datasource.p6spy.enable-logging=false",
                "--logging.level.root=WARN"
        ));
        try(var context=SpringApplication.run(TaskPilotApplication.class,properties.toArray(String[]::new))) {
            assertThat(context.getBean(Flyway.class).getConfiguration().getDefaultSchema()).isEqualTo("taskpilot_migrations");
            try(var manager=context.getBean(EntityManagerFactory.class).createEntityManager()) {
                var probe=manager.find(MigrationProbe.class,1L);
                assertThat(probe).isNotNull();assertThat(probe.title).isEqualTo("PostgreSQL fixture");
                manager.getTransaction().begin();
                var saved=new MigrationProbe();saved.id=2L;saved.title="Actual Hibernate write";
                manager.persist(saved);manager.getTransaction().commit();
            }
            int port=((WebServerApplicationContext)context).getWebServer().getPort();
            var response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("UP");
        }
    }

    @Entity(name="MigrationPolicyProbe")
    @Table(name="migration_schema_probe",schema="public")
    public static class MigrationProbe {
        @Id Long id;
        String title;
        public MigrationProbe() {}
    }
}
