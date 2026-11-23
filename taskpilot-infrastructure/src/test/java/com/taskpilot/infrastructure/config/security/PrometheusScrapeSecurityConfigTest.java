package com.taskpilot.infrastructure.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrometheusScrapeSecurityConfigTest {

    @Test
    void whenConfigured_requiresValidBasicAuthForPrometheusEndpoint() throws Exception {
        try (AnnotationConfigWebApplicationContext context = createContext("prometheus", "s3cr3t-scrape-pass")) {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(springSecurity())
                    .build();

            // 1. Missing credentials -> 401 with Basic challenge
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", containsString("Basic")));

            // 2. Wrong password -> 401
            mockMvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", "wrong-pass")))
                    .andExpect(status().isUnauthorized());

            // 3. Wrong username -> 401
            mockMvc.perform(get("/actuator/prometheus").with(httpBasic("admin", "s3cr3t-scrape-pass")))
                    .andExpect(status().isUnauthorized());

            // 4. Bearer JWT header is NOT accepted on this Basic-only chain -> 401
            mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer fake-admin-jwt"))
                    .andExpect(status().isUnauthorized());

            // 5. Valid Basic credentials -> 200
            mockMvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", "s3cr3t-scrape-pass")))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok"));

            // 6. Isolated: does NOT register global UserDetailsService / AuthenticationProvider / AuthenticationManager beans
            assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
            assertThat(context.getBeansOfType(AuthenticationProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(AuthenticationManager.class)).isEmpty();

            // 7. Only matches /actuator/prometheus (catch-all chain still permits /actuator/health and protects /api/**)
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("UP"));
            mockMvc.perform(get("/api/v1/protected"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void whenPasswordBlankOrWhitespace_deniesAllAccessEvenWithBasicCredentials() throws Exception {
        for (String blankPassword : new String[] {"", "   "}) {
            try (AnnotationConfigWebApplicationContext context = createContext("prometheus", blankPassword)) {
                MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                        .apply(springSecurity())
                        .build();

                mockMvc.perform(get("/actuator/prometheus"))
                        .andExpect(status().isForbidden());

                mockMvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", blankPassword)))
                        .andExpect(status().isForbidden());

                mockMvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", "any-password")))
                        .andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void whenPasswordExceeds72Bytes_startsCleanlyAndVerifiesFullPasswordWithoutTruncation() throws Exception {
        String prefix72 = "a".repeat(72);
        String longPassword = prefix72 + "_tail_beyond_bcrypt_72_bytes_limit_2026_secret";
        String wrongTailPassword = prefix72 + "_different_tail_beyond_72_bytes";

        try (AnnotationConfigWebApplicationContext context = createContext("prometheus", longPassword)) {
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(springSecurity())
                    .build();

            // Must NOT accept truncated 72-byte prefix or different suffix
            mockMvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", prefix72)))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", wrongTailPassword)))
                    .andExpect(status().isUnauthorized());

            // Must accept exact >72-byte password
            mockMvc.perform(get("/actuator/prometheus").with(httpBasic("prometheus", longPassword)))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok"));
        }
    }

    private static AnnotationConfigWebApplicationContext createContext(String username, String password) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        MockEnvironment env = new MockEnvironment()
                .withProperty("taskpilot.monitoring.scrape-username", username)
                .withProperty("taskpilot.monitoring.scrape-password", password);
        context.setEnvironment(env);
        context.register(TestWebSecurityApp.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(PrometheusScrapeSecurityConfig.class)
    static class TestWebSecurityApp {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        // Low-precedence catch-all chain simulating SecurityConfig without @Order
        @Bean
        SecurityFilterChain catchAllFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/actuator/health").permitAll()
                            .anyRequest().authenticated())
                    .build();
        }

        @Bean
        DummyPrometheusController dummyPrometheusController() {
            return new DummyPrometheusController();
        }
    }

    @RestController
    static class DummyPrometheusController {
        @GetMapping("/actuator/prometheus")
        String scrape() {
            return "ok";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @GetMapping("/api/v1/protected")
        String protectedRoute() {
            return "secret";
        }
    }
}
