package com.taskpilot.app;

import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.repository.AiLogRepository;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.infrastructure.util.JpaSortUtils;
import com.taskpilot.projects.common.repository.*;
import com.taskpilot.users.common.entity.NotificationEntity;
import com.taskpilot.users.common.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Meta;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpringDataModernizationTest {

    private static final List<Class<?>> REPOSITORIES_WITH_QUERIES = List.of(
            ChatMessageRepository.class,
            ChatSessionRepository.class,
            AiLogRepository.class,
            CommentRepository.class,
            ProjectMemberRepository.class,
            TaskRepository.class,
            TaskLabelRepository.class,
            TaskRequiredSkillRepository.class,
            UserSkillRepository.class,
            UserRepository.class,
            NotificationRepository.class,
            SystemSettingRepository.class
    );

    @Test
    @DisplayName("Modern Standard 11: getReferenceById must be inherited by all entity repositories")
    void testReferenceByIdInheritance() throws NoSuchMethodException {
        Method getReferenceByIdMethod = JpaRepository.class.getMethod("getReferenceById", Object.class);
        assertNotNull(getReferenceByIdMethod, "JpaRepository must declare getReferenceById(id)");

        for (Class<?> repoClass : REPOSITORIES_WITH_QUERIES) {
            assertTrue(JpaRepository.class.isAssignableFrom(repoClass),
                    repoClass.getSimpleName() + " must extend JpaRepository");
        }
    }

    @Test
    @DisplayName("Modern Standard 4: ChatMessageRepository and NotificationRepository declare Window Keyset methods")
    void testKeysetPaginationWindowMethods() throws NoSuchMethodException {
        // ChatMessageRepository
        Method chatWindowMethod = ChatMessageRepository.class.getMethod(
                "findBySessionIdOrderByCreatedAtDescIdDesc",
                Long.class, ScrollPosition.class, Limit.class
        );
        assertNotNull(chatWindowMethod);
        assertEquals(Window.class, chatWindowMethod.getReturnType(),
                "ChatMessageRepository keyset method must return Window<ChatMessageEntity>");

        // NotificationRepository
        Method notifWindowMethod = NotificationRepository.class.getMethod(
                "findByUserIdOrderByCreatedAtDescIdDesc",
                Long.class, ScrollPosition.class, Limit.class
        );
        assertNotNull(notifWindowMethod);
        assertEquals(Window.class, notifWindowMethod.getReturnType(),
                "NotificationRepository keyset method must return Window<NotificationEntity>");

        // Verify ScrollPosition instantiation
        ScrollPosition initialPosition = ScrollPosition.keyset();
        assertTrue(initialPosition.isInitial(), "Initial keyset scroll position must be marked initial");
    }

    @Test
    @DisplayName("Modern Standard 6: 100% of custom @Query and @NativeQuery methods must possess @Meta comments")
    void testObservabilityMetaAnnotationsOnAllQueries() {
        int validatedQueries = 0;

        for (Class<?> repoClass : REPOSITORIES_WITH_QUERIES) {
            for (Method method : repoClass.getDeclaredMethods()) {
                boolean hasQuery = method.isAnnotationPresent(Query.class);
                boolean hasNative = method.isAnnotationPresent(NativeQuery.class);

                if (hasQuery || hasNative) {
                    Meta meta = method.getAnnotation(Meta.class);
                    assertNotNull(meta, String.format(
                            "Method %s.%s must have @Meta annotation for observability",
                            repoClass.getSimpleName(), method.getName()
                    ));

                    assertFalse(meta.comment().isBlank(), String.format(
                            "@Meta comment on %s.%s must not be blank",
                            repoClass.getSimpleName(), method.getName()
                    ));

                    assertTrue(meta.comment().startsWith(repoClass.getSimpleName() + "."), String.format(
                            "@Meta comment '%s' on %s.%s must follow '<RepoName>.<methodName>' standard",
                            meta.comment(), repoClass.getSimpleName(), method.getName()
                    ));

                    validatedQueries++;
                }
            }
        }

        assertTrue(validatedQueries >= 18,
                "Expected at least 18 annotated custom queries across repositories, found: " + validatedQueries);
    }

    @Test
    @DisplayName("Modern Standard 5: Dedicated @NativeQuery annotation properly configured")
    void testDedicatedNativeQueryAnnotation() throws NoSuchMethodException {
        // TaskRepository native query
        Method taskNativeMethod = TaskRepository.class.getMethod("countTasksByStatusNative", Long.class);
        assertNotNull(taskNativeMethod);
        assertTrue(taskNativeMethod.isAnnotationPresent(NativeQuery.class));
        NativeQuery taskNQ = taskNativeMethod.getAnnotation(NativeQuery.class);
        assertTrue(taskNQ.value().contains("FROM tasks WHERE project_id = :projectId"));

        // AiLogRepository native query
        Method aiNativeMethod = AiLogRepository.class.getMethod("countLogsByEndpointNative", java.time.Instant.class);
        assertNotNull(aiNativeMethod);
        assertTrue(aiNativeMethod.isAnnotationPresent(NativeQuery.class));
    }

    @Test
    @DisplayName("Modern Standard 7: JpaSortUtils.unsafe correctly builds expression-based sorting")
    void testJpaSortUnsafe() {
        Sort sort = JpaSortUtils.unsafe("deadline ASC NULLS LAST");
        assertNotNull(sort);
        assertTrue(sort.isSorted());

        Sort.Order order = sort.iterator().next();
        assertEquals("deadline ASC NULLS LAST", order.getProperty());

        Sort directedSort = JpaSortUtils.unsafe(Sort.Direction.DESC, "LENGTH(title)");
        assertNotNull(directedSort);
        Sort.Order directedOrder = directedSort.iterator().next();
        assertEquals("LENGTH(title)", directedOrder.getProperty());
        assertEquals(Sort.Direction.DESC, directedOrder.getDirection());
    }

    @Test
    @DisplayName("Modern Standard 10: TaskRepository cleanly composites TaskSearchFragment")
    void testFragmentComposition() {
        assertTrue(TaskSearchFragment.class.isAssignableFrom(TaskRepository.class),
                "TaskRepository must extend TaskSearchFragment");

        assertTrue(TaskSearchFragment.class.isAssignableFrom(TaskSearchFragmentImpl.class),
                "TaskSearchFragmentImpl must implement TaskSearchFragment");

        boolean hasSearchMethod = Arrays.stream(TaskRepository.class.getMethods())
                .anyMatch(m -> m.getName().equals("searchTasksCustom"));
        assertTrue(hasSearchMethod, "TaskRepository must expose searchTasksCustom from composed fragment");
    }
}
