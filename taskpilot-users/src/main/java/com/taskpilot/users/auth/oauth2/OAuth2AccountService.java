package com.taskpilot.users.auth.oauth2;

import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.entity.UserIdentityEntity;
import com.taskpilot.users.common.enums.UserRole;
import com.taskpilot.users.common.enums.UserStatus;
import com.taskpilot.users.common.repository.UserIdentityRepository;
import com.taskpilot.users.common.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2AccountService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    @Transactional
    public UserEntity resolveUser(OAuth2UserInfo userInfo) {
        if (userInfo.provider() == null || userInfo.providerSubject() == null || userInfo.providerSubject().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "provider_error");
        }

        // 1. Kiểm tra danh tính đã liên kết trước đó chưa
        var existingIdentity = userIdentityRepository.findByProviderAndProviderSubject(
                userInfo.provider(), userInfo.providerSubject());

        if (existingIdentity.isPresent()) {
            UserIdentityEntity identity = existingIdentity.get();
            identity.setLastLoginAt(Instant.now());
            if (userInfo.email() != null && !userInfo.email().isBlank()) {
                identity.setEmail(userInfo.email());
            }
            userIdentityRepository.save(identity);
            log.info("OAuth2 login: matched existing identity provider={}, userId={}",
                    userInfo.provider(), identity.getUser().getId());
            return identity.getUser();
        }

        // 2. Nếu là danh tính mới, bắt buộc email phải đã được nhà cung cấp xác minh
        if (!userInfo.emailVerified() || userInfo.email() == null || userInfo.email().isBlank()) {
            log.warn("OAuth2 login rejected: unverified email or missing email from provider={}", userInfo.provider());
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "email_unverified");
        }

        String normalizedEmail = userInfo.email().trim().toLowerCase();

        // 3. Tra cứu người dùng sẵn có theo email đã xác minh
        var existingUserOpt = userRepository.findByEmail(normalizedEmail);
        UserEntity user;

        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            log.info("OAuth2 login: linking provider={} to existing user email={}", userInfo.provider(), normalizedEmail);
            // Chỉ cập nhật avatar nếu người dùng chưa có
            if ((user.getAvatarUrl() == null || user.getAvatarUrl().isBlank())
                    && userInfo.avatarUrl() != null && !userInfo.avatarUrl().isBlank()) {
                user.setAvatarUrl(userInfo.avatarUrl());
                userRepository.save(user);
            }
        } else {
            // 4. Tạo tài khoản người dùng mới
            log.info("OAuth2 register: creating new user from provider={} with email={}", userInfo.provider(), normalizedEmail);
            user = UserEntity.builder()
                    .email(normalizedEmail)
                    .fullName(userInfo.fullName() != null && !userInfo.fullName().isBlank() ? userInfo.fullName() : normalizedEmail.split("@")[0])
                    .avatarUrl(userInfo.avatarUrl())
                    .password(null)
                    .role(UserRole.USER)
                    .status(UserStatus.AVAILABLE)
                    .currentWorkload(0)
                    .build();
            user = userRepository.save(user);
        }

        // 5. Lưu liên kết danh tính mới
        UserIdentityEntity newIdentity = UserIdentityEntity.builder()
                .user(user)
                .provider(userInfo.provider())
                .providerSubject(userInfo.providerSubject())
                .email(normalizedEmail)
                .lastLoginAt(Instant.now())
                .build();
        userIdentityRepository.save(newIdentity);

        return user;
    }
}
