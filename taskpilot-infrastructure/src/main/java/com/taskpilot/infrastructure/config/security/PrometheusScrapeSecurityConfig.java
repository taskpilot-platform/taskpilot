package com.taskpilot.infrastructure.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Isolated HTTP Basic security chain exclusively for {@code /actuator/prometheus}.
 * Runs before the main JWT security chain ({@code @Order(1)}) without registering any global
 * {@code UserDetailsService}, {@code AuthenticationProvider}, or {@code AuthenticationManager} beans.
 * Fails closed (denies all requests) whenever {@code taskpilot.monitoring.scrape-password} is blank.
 */
@Configuration
public class PrometheusScrapeSecurityConfig {

    private static final String PROMETHEUS_ROLE = "PROMETHEUS";

    @Bean
    @Order(1)
    public SecurityFilterChain prometheusScrapeFilterChain(
            HttpSecurity http,
            PasswordEncoder passwordEncoder,
            @Value("${taskpilot.monitoring.scrape-username:prometheus}") String scrapeUsername,
            @Value("${taskpilot.monitoring.scrape-password:}") String scrapePassword) throws Exception {

        http.securityMatcher("/actuator/prometheus")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (scrapePassword == null || scrapePassword.isBlank()) {
            http.httpBasic(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
            return http.build();
        }

        String effectiveUsername = (scrapeUsername == null || scrapeUsername.isBlank())
                ? "prometheus"
                : scrapeUsername.trim();

        // Pre-hash via SHA-256 (always 64 ASCII bytes <= 72-byte BCrypt limit) so arbitrary-length
        // scrape secrets never crash BCryptPasswordEncoder at startup and never truncate past 72 bytes.
        PasswordEncoder safeEncoder = sha256PreHashingEncoder(passwordEncoder);

        UserDetails scrapeUser = User.withUsername(effectiveUsername)
                .password(safeEncoder.encode(scrapePassword))
                .roles(PROMETHEUS_ROLE)
                .build();

        InMemoryUserDetailsManager userDetailsManager = new InMemoryUserDetailsManager(scrapeUser);
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsManager);
        authProvider.setPasswordEncoder(safeEncoder);
        ProviderManager authManager = new ProviderManager(authProvider);

        http.authenticationManager(authManager)
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(PROMETHEUS_ROLE));

        return http.build();
    }

    private static PasswordEncoder sha256PreHashingEncoder(PasswordEncoder delegate) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return delegate.encode(sha256Hex(rawPassword));
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                if (rawPassword == null || encodedPassword == null) {
                    return false;
                }
                return delegate.matches(sha256Hex(rawPassword), encodedPassword);
            }
        };
    }

    private static String sha256Hex(CharSequence raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
