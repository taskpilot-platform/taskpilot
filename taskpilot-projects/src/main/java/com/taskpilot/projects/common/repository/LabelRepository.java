package com.taskpilot.projects.common.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.LabelEntity;

public interface LabelRepository extends JpaRepository<LabelEntity, Long> {
    List<LabelEntity> findByProjectId(Long projectId);

    boolean existsByProjectIdAndNameIgnoreCase(Long projectId, String name);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM LabelEntity l WHERE l.projectId = :projectId")
    void deleteByProjectId(@org.springframework.data.repository.query.Param("projectId") Long projectId);
}
