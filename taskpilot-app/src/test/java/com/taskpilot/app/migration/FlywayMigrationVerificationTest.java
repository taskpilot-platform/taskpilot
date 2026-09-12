package com.taskpilot.app.migration;

import io.github.cdimascio.dotenv.Dotenv;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationVerificationTest {

    @Test
    @DisplayName("Verify Flyway migration V22 successfully creates RAG tables in PostgreSQL")
    void testFlywayMigrationV22() {
        String envDir = new File(".env").exists() ? "./" : "../";
        Dotenv dotenv = Dotenv.configure().directory(envDir).ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });

        String dbUrl = System.getProperty("DB_URL");
        String dbUser = System.getProperty("DB_USERNAME");
        String dbPassword = System.getProperty("DB_PASSWORD");

        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = System.getenv("DB_URL");
            dbUser = System.getenv("DB_USERNAME");
            dbPassword = System.getenv("DB_PASSWORD");
        }

        assertThat(dbUrl)
                .as("Database URL must be configured for migration verification")
                .isNotBlank();

        Flyway flyway = Flyway.configure()
                .dataSource(dbUrl, dbUser, dbPassword)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(false)
                .load();

        flyway.migrate();

        MigrationInfo[] applied = flyway.info().applied();
        System.out.println("Total applied migrations: " + applied.length);
        for (MigrationInfo info : applied) {
            System.out.println("Migration: " + info.getVersion() + " - " + info.getDescription() + " [" + info.getState() + "]");
        }

        boolean v22Applied = Arrays.stream(applied)
                .anyMatch(m -> "22".equals(m.getVersion().getVersion()) && m.getState().isApplied());

        assertThat(v22Applied)
                .as("Migration V22__create_rag_tables.sql should be applied successfully")
                .isTrue();
    }
}
