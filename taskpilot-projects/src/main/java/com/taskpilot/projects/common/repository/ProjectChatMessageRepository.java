package com.taskpilot.projects.common.repository;

import com.taskpilot.projects.common.entity.ProjectChatMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectChatMessageRepository extends JpaRepository<ProjectChatMessageEntity, Long> {

    Page<ProjectChatMessageEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    List<ProjectChatMessageEntity> findTop50ByProjectIdOrderByCreatedAtDesc(Long projectId);

    long countByProjectId(Long projectId);
}
