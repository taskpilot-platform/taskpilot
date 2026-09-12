package com.taskpilot.app;

import com.taskpilot.ai.entity.AiChatRequestEntity;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.projects.common.entity.CommentEntity;
import com.taskpilot.projects.common.entity.LabelEntity;
import com.taskpilot.projects.common.entity.ProjectEntity;
import com.taskpilot.projects.common.entity.SprintEntity;
import com.taskpilot.projects.common.entity.TaskEntity;
import com.taskpilot.users.common.entity.NotificationEntity;
import com.taskpilot.users.common.entity.PasswordResetTokenEntity;
import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.SkillEntity;
import com.taskpilot.users.common.entity.UserEntity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EntitySequenceMappingTest {

    private static final List<Class<?>> TARGET_ENTITIES = List.of(
            UserEntity.class,
            ProjectEntity.class,
            TaskEntity.class,
            SprintEntity.class,
            LabelEntity.class,
            SkillEntity.class,
            NotificationEntity.class,
            CommentEntity.class,
            ChatSessionEntity.class,
            ChatMessageEntity.class,
            AiLogEntity.class,
            AiChatRequestEntity.class,
            RefreshTokenEntity.class,
            PasswordResetTokenEntity.class
    );

    @Test
    @DisplayName("Verify all 14 entities declare dedicated sequence generators with allocationSize = 1")
    void testAllEntitiesUseDedicatedSequences() {
        Set<String> generatorNames = new HashSet<>();
        Set<String> sequenceNames = new HashSet<>();

        for (Class<?> entityClass : TARGET_ENTITIES) {
            Table tableAnn = entityClass.getAnnotation(Table.class);
            assertNotNull(tableAnn, "Entity " + entityClass.getSimpleName() + " must have @Table annotation");
            String tableName = tableAnn.name();
            assertFalse(tableName.isBlank(), "Table name must not be blank for " + entityClass.getSimpleName());

            Field idField = findIdField(entityClass);
            assertNotNull(idField, "Entity " + entityClass.getSimpleName() + " must have an @Id field");

            GeneratedValue genVal = idField.getAnnotation(GeneratedValue.class);
            assertNotNull(genVal, "@Id field in " + entityClass.getSimpleName() + " must have @GeneratedValue");
            assertEquals(GenerationType.SEQUENCE, genVal.strategy(),
                    "Strategy in " + entityClass.getSimpleName() + " must be SEQUENCE");

            SequenceGenerator seqGen = idField.getAnnotation(SequenceGenerator.class);
            assertNotNull(seqGen, "@Id field in " + entityClass.getSimpleName() + " must have @SequenceGenerator");

            String expectedSeqName = tableName + "_id_seq";
            String expectedGenName = tableName + "_id_seq_gen";

            assertEquals(expectedGenName, seqGen.name(),
                    "Generator name mismatch for " + entityClass.getSimpleName());
            assertEquals(expectedSeqName, seqGen.sequenceName(),
                    "Sequence name mismatch for " + entityClass.getSimpleName());
            assertEquals(1, seqGen.allocationSize(),
                    "allocationSize must be 1 for zero-gap and sync with PostgreSQL INCREMENT BY 1 in " + entityClass.getSimpleName());

            assertTrue(generatorNames.add(seqGen.name()),
                    "Duplicate generator name detected: " + seqGen.name());
            assertTrue(sequenceNames.add(seqGen.sequenceName()),
                    "Duplicate sequence name detected: " + seqGen.sequenceName());
        }

        assertEquals(14, generatorNames.size(), "Should have exactly 14 unique sequence generators");
        assertEquals(14, sequenceNames.size(), "Should have exactly 14 unique PostgreSQL sequences");
    }

    private Field findIdField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
