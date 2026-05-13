package com.taskpilot.projects.common.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.CommentEntity;

@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, Long> {

    List<CommentEntity> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    Optional<CommentEntity> findByIdAndTaskId(Long id, Long taskId);

    @Query("SELECT DISTINCT c.userId FROM CommentEntity c WHERE c.taskId = :taskId")
    Set<Long> findParticipantUserIdsByTaskId(@Param("taskId") Long taskId);

    @Query(value = """
            SELECT c FROM CommentEntity c
            JOIN TaskEntity t ON t.id = c.taskId
            JOIN ProjectEntity p ON p.id = t.projectId
            JOIN ProjectMemberEntity pm ON pm.projectId = p.id AND pm.userId = :requesterUserId
            WHERE (:projectId IS NULL OR p.id = :projectId)
              AND (:taskId IS NULL OR t.id = :taskId)
              AND (:authorId IS NULL OR c.userId = :authorId)
              AND (:mentionedMe = false OR EXISTS (
                    SELECT cm FROM CommentMentionEntity cm
                    WHERE cm.commentId = c.id AND cm.userId = :requesterUserId
                  ))
              AND (:hasKeyword = false OR (
                    (c.deletedAt IS NULL AND LOWER(c.content) LIKE :keywordPattern)
                    OR LOWER(t.title) LIKE :keywordPattern
                    OR LOWER(p.name) LIKE :keywordPattern
                    OR (:hasKeywordAuthorMatches = true AND c.userId IN :keywordAuthorIds)
                  ))
            """,
            countQuery = """
                    SELECT COUNT(c) FROM CommentEntity c
                    JOIN TaskEntity t ON t.id = c.taskId
                    JOIN ProjectEntity p ON p.id = t.projectId
                    JOIN ProjectMemberEntity pm ON pm.projectId = p.id AND pm.userId = :requesterUserId
                    WHERE (:projectId IS NULL OR p.id = :projectId)
                      AND (:taskId IS NULL OR t.id = :taskId)
                      AND (:authorId IS NULL OR c.userId = :authorId)
                      AND (:mentionedMe = false OR EXISTS (
                            SELECT cm FROM CommentMentionEntity cm
                            WHERE cm.commentId = c.id AND cm.userId = :requesterUserId
                          ))
                      AND (:hasKeyword = false OR (
                            (c.deletedAt IS NULL AND LOWER(c.content) LIKE :keywordPattern)
                            OR LOWER(t.title) LIKE :keywordPattern
                            OR LOWER(p.name) LIKE :keywordPattern
                            OR (:hasKeywordAuthorMatches = true AND c.userId IN :keywordAuthorIds)
                          ))
                    """)
    Page<CommentEntity> searchAccessibleComments(
            @Param("requesterUserId") Long requesterUserId,
            @Param("hasKeyword") boolean hasKeyword,
            @Param("keywordPattern") String keywordPattern,
            @Param("projectId") Long projectId,
            @Param("taskId") Long taskId,
            @Param("authorId") Long authorId,
            @Param("mentionedMe") boolean mentionedMe,
            @Param("keywordAuthorIds") Set<Long> keywordAuthorIds,
            @Param("hasKeywordAuthorMatches") boolean hasKeywordAuthorMatches,
            Pageable pageable);
}
