package com.taskpilot.infrastructure.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.infrastructure.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@RequiredArgsConstructor
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
        private final JwtAuthenticationFilter jwtAuthFilter;
        private static final ObjectMapper objectMapper = new ObjectMapper();

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http)
                        throws Exception {
                http.csrf(AbstractHttpConfigurer::disable)
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers("/actuator/health").permitAll()
                                                .requestMatchers("/actuator/**").hasRole("ADMIN")
                                                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                                                .requestMatchers("/api/v1/auth/logout").authenticated()
                                                .requestMatchers(
                                                                "/api/v1/auth/register",
                                                                "/api/v1/auth/login",
                                                                "/api/v1/auth/refresh",
                                                                "/api/v1/auth/forgot-password",
                                                                "/api/v1/auth/reset-password")
                                                .permitAll()
                                                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                                                "/swagger-ui.html")
                                                .permitAll()
                                                .anyRequest().authenticated())
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint((request, response, authException) -> {
                                                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                                                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                                        ApiResponse<Void> apiResponse = ApiResponse.error(
                                                                        HttpStatus.UNAUTHORIZED.value(),
                                                                        "Unauthorized. Please provide a valid token.");
                                                        response.getWriter().write(
                                                                        objectMapper.writeValueAsString(apiResponse));
                                                })
                                                .accessDeniedHandler((request, response, accessDeniedException) -> {
                                                        response.setStatus(HttpStatus.FORBIDDEN.value());
                                                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                                                        ApiResponse<Void> apiResponse = ApiResponse.error(
                                                                        HttpStatus.FORBIDDEN.value(),
                                                                        "Access denied. Admin role required.");
                                                        response.getWriter().write(
                                                                        objectMapper.writeValueAsString(apiResponse));
                                                }))
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
                return http.build();
        }
}
