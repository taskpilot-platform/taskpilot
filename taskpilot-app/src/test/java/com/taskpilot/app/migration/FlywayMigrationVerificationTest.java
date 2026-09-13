package com.taskpilot.app.migration;

import io.github.cdimascio.dotenv.Dotenv;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.File;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationVerificationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String dbUrl;
    private static String dbUser;
    private static String dbPassword;

    @BeforeAll
    static void setUp() {
        boolean dockerAvailable = false;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            dockerAvailable = false;
        }

        if (dockerAvailable) {
            try {
                postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                        .withDatabaseName("taskpilot_test")
                        .withUsername("test")
                        .withPassword("test");
                postgres.start();
                dbUrl = postgres.getJdbcUrl();
                dbUser = postgres.getUsername();
                dbPassword = postgres.getPassword();
            } catch (Throwable t) {
                if (postgres != null) {
                    try {
                        postgres.stop();
                    } catch (Throwable ignored) {
                    }
                    postgres = null;
                }
            }
        }

        if (dbUrl == null || dbUrl.isBlank()) {
            String envDir = new File(".env").exists() ? "./" : "../";
            Dotenv dotenv = Dotenv.configure().directory(envDir).ignoreIfMissing().load();
            dotenv.entries().forEach(entry -> {
                if (System.getProperty(entry.getKey()) == null) {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            });

            dbUrl = System.getProperty("DB_URL");
            if (dbUrl == null || dbUrl.isBlank()) {
                dbUrl = System.getenv("DB_URL");
            }
            dbUser = System.getProperty("DB_USERNAME");
            if (dbUser == null || dbUser.isBlank()) {
                dbUser = System.getenv("DB_USERNAME");
            }
            dbPassword = System.getProperty("DB_PASSWORD");
            if (dbPassword == null || dbPassword.isBlank()) {
                dbPassword = System.getenv("DB_PASSWORD");
            }
        }
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("Verify Flyway migrations successfully apply in PostgreSQL (Testcontainers / Configured DB)")
    void testFlywayMigrationV22AndV28() {
        Assumptions.assumeTrue(
                dbUrl != null && !dbUrl.isBlank(),
                "Skipping Flyway migration verification: Neither Docker nor DB_URL is configured"
        );

        Flyway flyway = Flyway.configure()
                .dataSource(dbUrl, dbUser, dbPassword)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(false)
                .load();

        flyway.migrate();

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).isNotEmpty();
        System.out.println("Total applied migrations: " + applied.length);
        for (MigrationInfo info : applied) {
            System.out.println("Migration: " + info.getVersion() + " - " + info.getDescription() + " [" + info.getState() + "]");
        }

        boolean v22Applied = Arrays.stream(applied)
                .anyMatch(m -> "22".equals(m.getVersion().getVersion()) && m.getState().isApplied());
        assertThat(v22Applied)
                .as("Migration V22__create_rag_tables.sql should be applied successfully")
                .isTrue();

        boolean v28Applied = Arrays.stream(applied)
                .anyMatch(m -> "28".equals(m.getVersion().getVersion()) && m.getState().isApplied());
        assertThat(v28Applied)
                .as("Migration V28__migrate_identity_to_sequences.sql should be applied successfully")
                .isTrue();
    }
}
