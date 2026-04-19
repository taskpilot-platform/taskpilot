package com.taskpilot.users.repository;

import com.taskpilot.users.entity.UserSkillEntity;
import com.taskpilot.users.entity.UserSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserSkillRepository extends JpaRepository<UserSkillEntity, UserSkillId> {
    List<UserSkillEntity> findByIdUserId(Long userId);
    void deleteByIdUserId(Long userId);

    /** Eager fetch skills (JOIN FETCH) for AI AutoAssignment scoring — avoids LazyInitializationException */
    @Query("SELECT us FROM UserSkillEntity us JOIN FETCH us.skill WHERE us.id.userId = :userId")
    List<UserSkillEntity> findByIdUserIdWithSkill(@Param("userId") Long userId);
}

