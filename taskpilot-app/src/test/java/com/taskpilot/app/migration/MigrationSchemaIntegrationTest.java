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
