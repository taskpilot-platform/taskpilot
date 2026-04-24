package com.taskpilot.users.repository;

import com.taskpilot.users.entity.SkillEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, Long> {
    Optional<SkillEntity> findByName(String name);

    Optional<SkillEntity> findByIdAndIsActiveTrue(Long id);

    boolean existsByNameIgnoreCase(String name);

    Page<SkillEntity> findByNameContainingIgnoreCase(String name, Pageable pageable);

    List<SkillEntity> findByIsActiveTrueOrderByNameAsc();
}
