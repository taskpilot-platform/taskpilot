package com.taskpilot.app.migration;

import com.taskpilot.ai.entity.*;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import com.taskpilot.projects.common.entity.*;
import com.taskpilot.users.common.entity.*;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CI/CD & Local Pre-Push Guard:
 * Prevents production startup crashes (SchemaManagementException) caused by:
 * 1. Missing spring-boot-starter-flyway dependency in Spring Boot 4 modular auto-configuration.
 * 2. Adding JPA @Column fields without corresponding Flyway V<N> database migration scripts.
 * 3. Configuration regressions between production ddl-auto: validate and Flyway migrations.
 */
class EntityFlywaySchemaParityAuditTest {

    private static final List<Class<?>> ALL_JPA_ENTITIES = List.of(
            AiChatMemoryEntity.class,
            AiChatRequestEntity.class,
            AiLogEntity.class,
            ChatMessageEntity.class,
            ChatSessionEntity.class,
            DocumentEntity.class,
            CommentEntity.class,
            CommentMentionEntity.class,
            LabelEntity.class,
            ProjectChatMessageEntity.class,
            ProjectEntity.class,
            ProjectFileEntity.class,
            ProjectMeetingEntity.class,
            ProjectMeetingParticipantEntity.class,
            ProjectMemberEntity.class,
            SprintEntity.class,
            TaskEntity.class,
            TaskLabelEntity.class,
            TaskRequiredSkillEntity.class,
            NotificationEntity.class,
            PasswordResetTokenEntity.class,
            RefreshTokenEntity.class,
            SkillEntity.class,
            SystemSettingEntity.class,
            UserEntity.class,
            UserIdentityEntity.class,
            UserSkillEntity.class
    );

    @Test
    @DisplayName("Verify FlywayAutoConfiguration is present on classpath (spring-boot-starter-flyway)")
    void testFlywayAutoConfigurationPresentOnClasspath() {
        assertDoesNotThrow(() -> {
            Class.forName("org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration");
        }, "spring-boot-starter-flyway MUST be declared in taskpilot-app/pom.xml so Spring Boot 4 auto-configures Flyway on startup!");

        assertDoesNotThrow(() -> {
            Class.forName("org.flywaydb.core.Flyway");
        }, "flyway-core MUST be present on the classpath.");
    }

    @Test
    @DisplayName("Verify all Flyway SQL migration scripts exist, are ordered, and V40 is present")
    void testFlywayMigrationsExistAndOrdered() throws Exception {
        Path migrationDir = Paths.get("src/main/resources/db/migration");
        if (!Files.exists(migrationDir)) {
            migrationDir = Paths.get("taskpilot-app/src/main/resources/db/migration");
        }
        assertTrue(Files.exists(migrationDir), "Flyway migration directory db/migration must exist");

        List<Path> sqlFiles;
        try (Stream<Path> stream = Files.list(migrationDir)) {
            sqlFiles = stream
                    .filter(p -> p.toString().endsWith(".sql"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertThat(sqlFiles).isNotEmpty();
        boolean hasV40 = sqlFiles.stream().anyMatch(p -> p.getFileName().toString().startsWith("V40__"));
        assertTrue(hasV40, "Migration V40__add_meeting_notes_transcript_ai_summary.sql must exist");
    }

    @Test
    @DisplayName("Verify every JPA Entity column has a corresponding definition in Flyway migrations")
    void testAllEntityColumnsExistInFlywayMigrations() throws Exception {
        Path migrationDir = Paths.get("src/main/resources/db/migration");
        if (!Files.exists(migrationDir)) {
            migrationDir = Paths.get("taskpilot-app/src/main/resources/db/migration");
        }

        // Aggregate all SQL content
        StringBuilder fullSqlBuilder = new StringBuilder();
        try (Stream<Path> stream = Files.list(migrationDir)) {
            List<Path> sqlFiles = stream
                    .filter(p -> p.toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .collect(Collectors.toList());

            for (Path sqlFile : sqlFiles) {
                fullSqlBuilder.append("\n-- FILE: ").append(sqlFile.getFileName()).append("\n");
                fullSqlBuilder.append(Files.readString(sqlFile, StandardCharsets.UTF_8));
            }
        }
        String fullSql = fullSqlBuilder.toString().toLowerCase();

        List<String> missingColumnsReport = new ArrayList<>();

        for (Class<?> entityClass : ALL_JPA_ENTITIES) {
            Table tableAnn = entityClass.getAnnotation(Table.class);
            assertNotNull(tableAnn, "Entity " + entityClass.getSimpleName() + " must have @Table annotation");
            String tableName = tableAnn.name().toLowerCase().replace("\"", "");

            // Verify table exists in migrations
            boolean tableFound = fullSql.contains("create table \"" + tableName + "\"")
                    || fullSql.contains("create table " + tableName + " ")
                    || fullSql.contains("create table " + tableName + "(")
                    || fullSql.contains("create table if not exists \"" + tableName + "\"")
                    || fullSql.contains("create table if not exists " + tableName + " ");

            if (!tableFound) {
                missingColumnsReport.add("Table '" + tableName + "' for entity " + entityClass.getSimpleName() + " not found in any Flyway migration!");
                continue;
            }

            // Extract all JPA column names from fields
            List<Field> fields = getAllEntityFields(entityClass);
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Transient.class)) {
                    continue;
                }

                String columnName = null;
                Column colAnn = field.getAnnotation(Column.class);
                if (colAnn != null && !colAnn.name().isBlank()) {
                    columnName = colAnn.name();
                } else if (field.isAnnotationPresent(Id.class)) {
                    columnName = "id";
                }

                if (columnName != null) {
                    String cleanColName = columnName.toLowerCase().replace("\"", "").trim();

                    // Check if column exists in SQL
                    boolean columnFound = fullSql.contains("\"" + cleanColName + "\"")
                            || fullSql.contains(" " + cleanColName + " ")
                            || fullSql.contains("," + cleanColName + " ")
                            || fullSql.contains("(" + cleanColName + " ");

                    if (!columnFound) {
                        missingColumnsReport.add(String.format(
                                "Entity %s declares column '%s' on table '%s', but column '%s' is MISSING from Flyway migrations! "
                                        + "(Add a Flyway V<N> migration adding this column before pushing to prevent prod crash)",
                                entityClass.getSimpleName(), cleanColName, tableName, cleanColName
                        ));
                    }
                }
            }
        }

        if (!missingColumnsReport.isEmpty()) {
            fail("Schema Parity Audit FAILED:\n" + String.join("\n", missingColumnsReport));
        }
    }

    @Test
    @DisplayName("Verify ProjectMeetingEntity Phase 4 columns (notes, transcript, ai_summary, action_items_json) are in V40")
    void testProjectMeetingEntityPhase4ColumnsExplicitAudit() throws Exception {
        Path v40Path = Paths.get("src/main/resources/db/migration/V40__add_meeting_notes_transcript_ai_summary.sql");
        if (!Files.exists(v40Path)) {
            v40Path = Paths.get("taskpilot-app/src/main/resources/db/migration/V40__add_meeting_notes_transcript_ai_summary.sql");
        }
        assertTrue(Files.exists(v40Path), "V40 migration file must exist");
        String v40Sql = Files.readString(v40Path, StandardCharsets.UTF_8);

        assertThat(v40Sql)
                .contains("notes")
                .contains("transcript")
                .contains("ai_summary")
                .contains("ai_summary_generated_at")
                .contains("action_items_json");
    }

    private List<Field> getAllEntityFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
