package com.taskpilot.users.repository;

import com.taskpilot.users.entity.UserSkillEntity;
import com.taskpilot.users.entity.UserSkillId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserSkillRepository extends JpaRepository<UserSkillEntity, UserSkillId> {
    List<UserSkillEntity> findByIdUserId(Long userId);
    void deleteByIdUserId(Long userId);
}
