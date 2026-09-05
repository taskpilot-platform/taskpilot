package com.taskpilot.ai.rag.repository;

import com.taskpilot.ai.rag.domain.DocumentStatus;
import com.taskpilot.ai.rag.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findByProjectId(Long projectId);

    List<DocumentEntity> findByProjectIdAndStatus(Long projectId, DocumentStatus status);

    Optional<DocumentEntity> findByIdAndProjectId(Long id, Long projectId);

    void deleteByProjectId(Long projectId);
}
