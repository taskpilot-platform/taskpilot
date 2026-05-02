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

        @Query("SELECT pm FROM ProjectMemberEntity pm WHERE pm.userId = :userId "
                        + "AND (:keyword IS NULL OR LOWER(pm.project.name) LIKE LOWER(CONCAT('%', :keyword, '%')) "
                        + "OR LOWER(pm.project.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
        Page<ProjectMemberEntity> findProjectsByUserIdAndKeyword(
                        @Param("userId") Long userId,
                        @Param("keyword") String keyword,
                        Pageable pageable);

        @Query("SELECT pm FROM ProjectMemberEntity pm WHERE pm.projectId = :projectId")
        List<ProjectMemberEntity> findMembers(@Param("projectId") Long projectId);

        List<ProjectMemberEntity> findByProjectIdAndRole(Long projectId, ProjectMemberEntity.MemberRole role);

        Optional<ProjectMemberEntity> findByProjectIdAndUserId(Long projectId, Long userId);

        boolean existsByProjectIdAndUserId(Long projectId, Long userId);

        @Query("SELECT COUNT(pm) FROM ProjectMemberEntity pm WHERE pm.projectId = :projectId")
        long countMembers(@Param("projectId") Long projectId);

        @Query("SELECT pm.performanceScore FROM ProjectMemberEntity pm "
                        + "WHERE pm.userId = :userId AND pm.performanceScore IS NOT NULL "
                        + "ORDER BY pm.joinedAt DESC")
        List<Double> findRecentPerformanceScores(@Param("userId") Long userId, Pageable pageable);

        @Query("SELECT pm.project FROM ProjectMemberEntity pm "
                        + "WHERE pm.userId = :userId "
                        + "AND pm.project.endDate IS NOT NULL "
                        + "AND pm.project.endDate BETWEEN :fromDate AND :toDate "
                        + "ORDER BY pm.project.endDate ASC")
        List<com.taskpilot.projects.common.entity.ProjectEntity> findUpcomingProjects(
                        @Param("userId") Long userId,
                        @Param("fromDate") java.time.LocalDate fromDate,
                        @Param("toDate") java.time.LocalDate toDate,
                        Pageable pageable);
}
