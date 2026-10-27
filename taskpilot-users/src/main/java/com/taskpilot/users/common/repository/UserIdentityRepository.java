package com.taskpilot.users.common.repository;

import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserIdentityEntity;
import com.taskpilot.users.common.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, Long> {

    Optional<UserIdentityEntity> findByProviderAndProviderSubject(AuthProvider provider, String providerSubject);

    List<UserIdentityEntity> findByUser(UserEntity user);

    List<UserIdentityEntity> findByUserId(Long userId);

    boolean existsByProviderAndProviderSubject(AuthProvider provider, String providerSubject);
}
