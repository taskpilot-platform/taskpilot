package com.taskpilot.projects.common.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.ProjectEntity;

@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {
    boolean existsByName(String name);
    Optional<ProjectEntity> findByName(String name);
}
