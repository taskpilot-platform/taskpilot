package com.taskpilot.app.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationVerificationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("taskpilot_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    @DisplayName("Verify Flyway migrations successfully apply in PostgreSQL (Testcontainers)")
    void testFlywayMigrationV22AndV28() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
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

        boolean v29Applied = Arrays.stream(applied)
                .anyMatch(m -> "29".equals(m.getVersion().getVersion()) && m.getState().isApplied());
        assertThat(v29Applied)
                .as("Migration V29__simplify_staging_identity.sql should be applied successfully")
                .isTrue();
    }
}
