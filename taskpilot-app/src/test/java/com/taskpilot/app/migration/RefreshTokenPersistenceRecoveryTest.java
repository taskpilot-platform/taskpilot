package com.taskpilot.app.migration;

import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.RefreshTokenHistoryEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.repository.RefreshTokenHistoryRepository;
import com.taskpilot.users.common.repository.RefreshTokenRepository;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RefreshTokenPersistenceRecoveryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("taskpilot_refresh_recovery")
            .withUsername("test")
            .withPassword("test");

    @Test
    void tokenLookupLoadsUserBeforePersistenceContextClosesOnReconciledSchema() throws Exception {
        MigrationDatabaseConfiguration.configure(
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .load()
                .migrate();

        UUID sessionId = UUID.randomUUID();
        String activeHash = "a".repeat(64);
        String historyHash = "b".repeat(64);

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(id,email,full_name,password_hash,role) "
                    + "VALUES(900001,'refresh.recovery@example.invalid','Synthetic recovery','unused','USER')");
            statement.executeUpdate("INSERT INTO refresh_tokens(session_id,token_hash,token_version,expiry_date,user_id) "
                    + "VALUES('" + sessionId + "','" + activeHash + "',2,CURRENT_TIMESTAMP + INTERVAL '1 day',900001)");
            statement.executeUpdate("INSERT INTO refresh_token_history(session_id,user_id,token_hash,consumed_at,expires_at) "
                    + "VALUES('" + sessionId + "',900001,'" + historyHash + "',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 day')");
        }

        try (var factory = new Configuration()
                .addAnnotatedClass(UserEntity.class)
                .addAnnotatedClass(RefreshTokenEntity.class)
                .addAnnotatedClass(RefreshTokenHistoryEntity.class)
                .setProperty("hibernate.connection.url", postgres.getJdbcUrl())
                .setProperty("hibernate.connection.username", "test")
                .setProperty("hibernate.connection.password", "test")
                .setProperty("hibernate.hbm2ddl.auto", "validate")
                .buildSessionFactory()) {
            var entityManager = factory.createEntityManager();
            RefreshTokenEntity token;
            RefreshTokenHistoryEntity history;
            try {
                entityManager.getTransaction().begin();
                var repoFactory = new JpaRepositoryFactory(entityManager);
                var repository = repoFactory.getRepository(RefreshTokenRepository.class);
                var historyRepository = repoFactory.getRepository(RefreshTokenHistoryRepository.class);
                token = repository.findByTokenHashForUpdate(activeHash).orElseThrow();
                history = historyRepository.findByTokenHash(historyHash).orElseThrow();
                entityManager.getTransaction().commit();
            } finally {
                entityManager.close();
            }

            assertThat(token.getSessionId()).isEqualTo(sessionId);
            assertThat(token.getTokenHash()).isEqualTo(activeHash);
            assertThat(token.getTokenVersion()).isEqualTo(2L);
            assertThat(token.getUser().getEmail()).isEqualTo("refresh.recovery@example.invalid");
            assertThat(token.getUser().getRole().name()).isEqualTo("USER");
            assertThat(history.getSessionId()).isEqualTo(sessionId);
            assertThat(history.getUserId()).isEqualTo(900001L);
        }
    }
}
