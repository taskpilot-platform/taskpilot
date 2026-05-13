package com.taskpilot.projects.common.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.CommentMentionEntity;

@Repository
public interface CommentMentionRepository
        extends JpaRepository<CommentMentionEntity, CommentMentionEntity.CommentMentionId> {

    List<CommentMentionEntity> findByCommentId(Long commentId);

    List<CommentMentionEntity> findByCommentIdIn(Collection<Long> commentIds);

    void deleteByCommentId(Long commentId);
}
