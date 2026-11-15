package com.taskpilot.infrastructure.config.security.oauth2;

import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.LookupKey;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.Purpose;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.SaveCommand;
import com.taskpilot.infrastructure.config.security.oauth2.OAuth2AuthorizationRequestStore.StoredHandle;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class OAuth2AuthorizationRequestStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("oauth2_store_test")
            .withUsername("test")
            .withPassword("test");

    private static AnnotationConfigApplicationContext appContext;
    private static JdbcTemplate jdbcTemplate;
    private static OAuth2AuthorizationRequestJpaRepository jpaRepository;
    private static PlatformTransactionManager txManager;

    private MutableClock clock;
    private OAuth2Properties properties;
    private DatabaseOAuth2AuthorizationRequestStore storeA;
    private DatabaseOAuth2AuthorizationRequestStore storeB;

    @BeforeAll
    static void initContext() {
        Path reconciledDir = Path.of("../taskpilot-app/src/main/resources/db/reconciled").toAbsolutePath().normalize();
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("taskpilot_migrations", "public")
                .defaultSchema("taskpilot_migrations")
                .table("flyway_schema_history_v2")
                .locations("filesystem:" + reconciledDir)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
        flyway.migrate();

        appContext = new AnnotationConfigApplicationContext();
        appContext.registerBean("jdbcUrl", String.class, postgres::getJdbcUrl);
        appContext.registerBean("jdbcUser", String.class, postgres::getUsername);
        appContext.registerBean("jdbcPass", String.class, postgres::getPassword);
        appContext.register(JpaTestConfig.class);
        appContext.refresh();

        jdbcTemplate = appContext.getBean(JdbcTemplate.class);
        jpaRepository = appContext.getBean(OAuth2AuthorizationRequestJpaRepository.class);
        txManager = appContext.getBean(PlatformTransactionManager.class);
    }

    @AfterAll
    static void closeContext() {
        if (appContext != null) {
            appContext.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM public.oauth2_authorization_requests");
        clock = new MutableClock(Instant.parse("2026-11-15T12:00:00Z"));
        properties = new OAuth2Properties();
        properties.setRequestTtlSeconds(300);
        properties.setMaxPendingRequests(100);
        storeA = new DatabaseOAuth2AuthorizationRequestStore(jpaRepository, jdbcTemplate, properties, txManager, clock);
        storeB = new DatabaseOAuth2AuthorizationRequestStore(jpaRepository, jdbcTemplate, properties, txManager, clock);
    }

    @Test
    @Timeout(60)
    void nondestructiveLoadAndAtomicConsumeAcrossTwoStoreInstancesPreservePkceAndOidc() {
        OAuth2AuthorizationRequest req = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client-1")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(Set.of("openid", "email", "profile"))
                .state("state-cross-instance")
                .additionalParameters(p -> {
                    p.put("code_challenge", "pkce-challenge-123");
                    p.put("code_challenge_method", "S256");
                    p.put("nonce", "oidc-nonce-456");
                })
                .attributes(a -> {
                    a.put("registration_id", "google");
                    a.put("code_verifier", "pkce-verifier-secret-789");
                    a.put("nonce", "oidc-nonce-456");
                })
                .build();

        StoredHandle handle = storeA.put(new SaveCommand(
                req, "google", Purpose.LOGIN, null, "browser-nonce-1", req.getRedirectUri()));

        // Stored payload must be structured JSON, never Java serialization
        String storedJson = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM public.oauth2_authorization_requests WHERE handle_hash = ?",
                String.class,
                DatabaseOAuth2AuthorizationRequestStore.sha256Hex(handle.handle()));
        assertThat(storedJson).startsWith("{").doesNotStartWith("rO0AB")
                .contains("pkce-verifier-secret-789")
                .contains("oidc-nonce-456");

        LookupKey key = new LookupKey(
                handle.handle(), "browser-nonce-1", "state-cross-instance",
                "google", Purpose.LOGIN, req.getRedirectUri());

        // Instance B loads twice non-destructively
        assertThat(storeB.load(key)).isPresent();
        Optional<OAuth2AuthorizationRequest> secondLoad = storeB.load(key);
        assertThat(secondLoad).isPresent();
        assertThat((String) secondLoad.get().getAttribute("code_verifier")).isEqualTo("pkce-verifier-secret-789");
        assertThat(secondLoad.get().getAdditionalParameters()).containsEntry("nonce", "oidc-nonce-456");

        // Instance B consumes once; instance A cannot consume again
        assertThat(storeB.consume(key)).isPresent();
        assertThat(storeA.consume(key)).isEmpty();
        assertThat(storeB.load(key)).isEmpty();
    }

    @Test
    @Timeout(60)
    void concurrentDoubleConsumeHasExactlyOneWinner() throws Exception {
        OAuth2AuthorizationRequest req = sampleRequest("state-concurrent");
        StoredHandle handle = storeA.put(new SaveCommand(
                req, "google", Purpose.LOGIN, null, "nonce-race", req.getRedirectUri()));
        LookupKey key = new LookupKey(
                handle.handle(), "nonce-race", "state-concurrent",
                "google", Purpose.LOGIN, req.getRedirectUri());

        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Optional<OAuth2AuthorizationRequest>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                DatabaseOAuth2AuthorizationRequestStore targetStore = (i % 2 == 0) ? storeA : storeB;
                Callable<Optional<OAuth2AuthorizationRequest>> task = () -> {
                    ready.countDown();
                    go.await();
                    return targetStore.consume(key);
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();

            long winners = 0;
            for (Future<Optional<OAuth2AuthorizationRequest>> f : futures) {
                if (f.get().isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1L);
        }
    }

    @Test
    @Timeout(60)
    void concurrentPutEnforcesCapacityLimitWithoutSilentOverflow() throws Exception {
        properties.setMaxPendingRequests(3);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        OAuth2AuthorizationRequest req = sampleRequest("cap-state-" + idx);
                        storeA.put(new SaveCommand(req, "google", Purpose.LOGIN, null, "nonce-" + idx, req.getRedirectUri()));
                        return true;
                    } catch (IllegalStateException ex) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown();

            int succeeded = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(3);
            Long activeCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM public.oauth2_authorization_requests WHERE consumed_at IS NULL",
                    Long.class);
            assertThat(activeCount).isEqualTo(3L);
        }
    }

    @Test
    @Timeout(60)
    void expiredRequestFailsLookupAndPurgeCleansUpExpiredRows() {
        OAuth2AuthorizationRequest req = sampleRequest("state-ttl");
        StoredHandle handle = storeA.put(new SaveCommand(
                req, "google", Purpose.LOGIN, null, "nonce-ttl", req.getRedirectUri()));
        LookupKey key = new LookupKey(
                handle.handle(), "nonce-ttl", "state-ttl",
                "google", Purpose.LOGIN, req.getRedirectUri());

        clock.advanceSeconds(299);
        assertThat(storeA.load(key)).isPresent();

        clock.advanceSeconds(2);
        assertThat(storeA.load(key)).isEmpty();
        assertThat(storeA.consume(key)).isEmpty();
        assertThat(storeA.purgeExpired()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.oauth2_authorization_requests", Long.class)).isZero();
    }

    @Test
    @Timeout(60)
    void bindingMismatchesDoNotConsumeValidRowAndLinkPurposeRequiresTargetUser() {
        OAuth2AuthorizationRequest loginReq = sampleRequest("state-binding");
        StoredHandle handle = storeA.put(new SaveCommand(
                loginReq, "google", Purpose.LOGIN, null, "nonce-bind", loginReq.getRedirectUri()));

        assertThat(storeA.consume(new LookupKey(handle.handle(), "wrong-nonce", "state-binding", "google", Purpose.LOGIN, loginReq.getRedirectUri()))).isEmpty();
        assertThat(storeA.consume(new LookupKey(handle.handle(), "nonce-bind", "wrong-state", "google", Purpose.LOGIN, loginReq.getRedirectUri()))).isEmpty();
        assertThat(storeA.consume(new LookupKey(handle.handle(), "nonce-bind", "state-binding", "github", Purpose.LOGIN, loginReq.getRedirectUri()))).isEmpty();
        assertThat(storeA.consume(new LookupKey(handle.handle(), "nonce-bind", "state-binding", "google", Purpose.LINK, loginReq.getRedirectUri()))).isEmpty();
        assertThat(storeA.consume(new LookupKey(handle.handle(), "nonce-bind", "state-binding", "google", Purpose.LOGIN, "http://evil.invalid/cb"))).isEmpty();

        // Valid request remains intact after rejected attempts
        assertThat(storeA.consume(new LookupKey(
                handle.handle(), "nonce-bind", "state-binding",
                "google", Purpose.LOGIN, loginReq.getRedirectUri()))).isPresent();

        // LINK flow binds targetUserId cleanly
        OAuth2AuthorizationRequest linkReq = sampleRequest("state-link");
        StoredHandle linkHandle = storeA.put(new SaveCommand(
                linkReq, "google", Purpose.LINK, 42L, "nonce-link", linkReq.getRedirectUri()));
        assertThat(storeA.load(new LookupKey(
                linkHandle.handle(), "nonce-link", "state-link",
                "google", Purpose.LOGIN, linkReq.getRedirectUri()))).isEmpty();
        assertThat(storeA.consume(new LookupKey(
                linkHandle.handle(), "nonce-link", "state-link",
                "google", Purpose.LINK, linkReq.getRedirectUri()))).isPresent();
    }

    @Test
    @Timeout(60)
    void serverOnlyTableDeniesRestrictedApiRolesWhilePreservingOwnerAccess() throws Exception {
        OAuth2AuthorizationRequest req = sampleRequest("state-rls-secret");
        StoredHandle handle = storeA.put(new SaveCommand(
                req, "google", Purpose.LOGIN, null, "nonce-rls", req.getRedirectUri()));

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
                            CREATE ROLE anon NOLOGIN;
                        END IF;
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
                            CREATE ROLE authenticated NOLOGIN;
                        END IF;
                    END $$;
                    """);
            // 1) Default privileges after V43: restricted roles are denied at privilege level
            for (String role : List.of("anon", "authenticated")) {
                st.execute("SET ROLE " + role);
                assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM public.oauth2_authorization_requests"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.executeUpdate("DELETE FROM public.oauth2_authorization_requests"))
                        .isInstanceOf(SQLException.class);
                st.execute("RESET ROLE");
            }

            // 2) Even if table privileges are granted (as on Supabase default grants), RLS protects all rows
            st.execute("GRANT USAGE ON SCHEMA public TO anon, authenticated");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.oauth2_authorization_requests TO anon, authenticated");

            for (String role : List.of("anon", "authenticated")) {
                st.execute("SET ROLE " + role);
                try (var rs = st.executeQuery("SELECT count(*) FROM public.oauth2_authorization_requests")) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }
                assertThatThrownBy(() -> st.executeUpdate(
                        """
                        INSERT INTO public.oauth2_authorization_requests
                            (handle_hash, nonce_hash, state_hash, provider, purpose, redirect_uri, payload_json, expires_at)
                        VALUES ('h', 'n', 's', 'google', 'LOGIN', 'http://localhost/cb', '{}', NOW() + interval '5 minutes')
                        """))
                        .isInstanceOf(SQLException.class);
                assertThat(st.executeUpdate(
                        "UPDATE public.oauth2_authorization_requests SET consumed_at = NOW()"))
                        .isZero();
                assertThat(st.executeUpdate(
                        "DELETE FROM public.oauth2_authorization_requests"))
                        .isZero();
                st.execute("RESET ROLE");
            }
        }

        // Backend owner retains full read/consume access
        assertThat(storeA.consume(new LookupKey(
                handle.handle(), "nonce-rls", "state-rls-secret",
                "google", Purpose.LOGIN, req.getRedirectUri()))).isPresent();
    }

    private static OAuth2AuthorizationRequest sampleRequest(String state) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client-id")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scopes(Set.of("openid", "email"))
                .state(state)
                .attributes(attrs -> attrs.put("registration_id", "google"))
                .build();
    }

    @Configuration
    @EnableJpaRepositories(basePackageClasses = OAuth2AuthorizationRequestJpaRepository.class)
    static class JpaTestConfig {

        @Bean(destroyMethod = "close")
        DataSource dataSource(String jdbcUrl, String jdbcUser, String jdbcPass) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(jdbcUrl);
            ds.setUsername(jdbcUser);
            ds.setPassword(jdbcPass);
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(ds);
            emf.setPackagesToScan(OAuth2AuthorizationRequestEntity.class.getPackageName());
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"
            ));
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advanceSeconds(long seconds) {
            this.current = this.current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
