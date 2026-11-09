package com.taskpilot.app.migration;

import com.taskpilot.users.common.entity.RefreshTokenEntity;
import com.taskpilot.users.common.entity.UserEntity;
import com.taskpilot.users.common.repository.RefreshTokenRepository;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenPersistenceRecoveryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("taskpilot_refresh_recovery")
            .withUsername("test")
            .withPassword("test");

    @Test
    void tokenLookupLoadsUserBeforePersistenceContextCloses() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "test", "test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO users(id,email,full_name,password_hash,role) "
                    + "VALUES(900001,'refresh.recovery@example.invalid','Synthetic recovery','unused','USER')");
            statement.executeUpdate("INSERT INTO refresh_tokens(token,expiry_date,user_id) "
                    + "VALUES('synthetic-refresh-recovery',CURRENT_TIMESTAMP + INTERVAL '1 day',900001)");
        }

        try (var factory = new Configuration()
                .addAnnotatedClass(UserEntity.class).addAnnotatedClass(RefreshTokenEntity.class)
                .setProperty("hibernate.connection.url", postgres.getJdbcUrl())
                .setProperty("hibernate.connection.username", "test")
                .setProperty("hibernate.connection.password", "test")
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .buildSessionFactory()) {
            var entityManager = factory.createEntityManager();
            RefreshTokenEntity token;
            try {
                var repository = new JpaRepositoryFactory(entityManager).getRepository(RefreshTokenRepository.class);
                token = repository.findByToken("synthetic-refresh-recovery").orElseThrow();
            } finally {
                entityManager.close();
            }

            assertThat(token.getUser().getEmail()).isEqualTo("refresh.recovery@example.invalid");
            assertThat(token.getUser().getRole().name()).isEqualTo("USER");
        }
    }
}
