package com.taskpilot.app;

import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class InitUserPassword implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        log.info("[InitUserPassword] Checking/Updating test user password...");
        userRepository.findByEmail("dangphuthien2005@gmail.com").ifPresentOrElse(
            user -> {
                String encodedPassword = passwordEncoder.encode("abcdefghijkl");
                user.setPassword(encodedPassword);
                user.setRole(UserEntity.UserRole.ADMIN);
                userRepository.save(user);
                log.info("[InitUserPassword] Updated password for dangphuthien2005@gmail.com to abcdefghijkl and role to ADMIN");
            },
            () -> {
                log.warn("[InitUserPassword] dangphuthien2005@gmail.com not found, creating new user...");
                UserEntity newUser = UserEntity.builder()
                        .email("dangphuthien2005@gmail.com")
                        .password(passwordEncoder.encode("abcdefghijkl"))
                        .fullName("FuTie Neith")
                        .role(UserEntity.UserRole.ADMIN)
                        .build();
                userRepository.save(newUser);
                log.info("[InitUserPassword] Created user dangphuthien2005@gmail.com with password abcdefghijkl and role ADMIN");
            }
        );
    }
}
