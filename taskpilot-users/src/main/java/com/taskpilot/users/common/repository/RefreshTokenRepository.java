package com.taskpilot.users.common.repository;

import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByToken(String token);

    @Query("SELECT rt FROM RefreshTokenEntity rt JOIN FETCH rt.user WHERE rt.token = :token")
    Optional<RefreshTokenEntity> findByTokenWithUser(@Param("token") String token);

    void deleteByUser(UserEntity user);
}

