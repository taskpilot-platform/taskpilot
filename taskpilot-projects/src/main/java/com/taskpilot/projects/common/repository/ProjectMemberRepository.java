package com.taskpilot.projects.common.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.entity.ProjectMemberId;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMemberEntity, ProjectMemberId> {
    
    @Query("SELECT pm FROM ProjectMemberEntity pm WHERE pm.userId = :userId")
    Page<ProjectMemberEntity> findProjectsByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT pm FROM ProjectMemberEntity pm WHERE pm.projectId = :projectId")
    List<ProjectMemberEntity> findMembers(@Param("projectId") Long projectId);

    Optional<ProjectMemberEntity> findByProjectIdAndUserId(Long projectId, Long userId);

    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    @Query("SELECT COUNT(pm) FROM ProjectMemberEntity pm WHERE pm.projectId = :projectId")
    long countMembers(@Param("projectId") Long projectId);
}
