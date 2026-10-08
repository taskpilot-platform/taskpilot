package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.AiLogEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AiLogRepositoryPostgresTest.TestConfig.class)
class AiLogRepositoryPostgresTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("taskpilot_test")
            .withUsername("test")
            .withPassword("test");

    @Configuration
    @EnableJpaRepositories(basePackageClasses = AiLogRepository.class)
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            if (postgres == null || !postgres.isRunning()) {
                throw new IllegalStateException("PostgreSQLContainer is not running. External datasource fallback is forbidden.");
            }
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUsername(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            return dataSource;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
            em.setDataSource(dataSource);
            em.setPackagesToScan(AiLogEntity.class.getPackageName());

            HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            em.setJpaVendorAdapter(vendorAdapter);

            Properties properties = new Properties();
            properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            properties.setProperty("hibernate.hbm2ddl.auto", "update");
            properties.setProperty("hibernate.show_sql", "true");
            properties.setProperty("hibernate.format_sql", "true");
            em.setJpaProperties(properties);

            return em;
        }

        @Bean
        public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    @Autowired
    private AiLogRepository aiLogRepository;

    @Test
    @DisplayName("Scenario A: all optional filters null")
    void testA_allOptionalFiltersNull() {
        Page<AiLogEntity> page = aiLogRepository.findByFilters(
                null,
                null,
                null,
                null,
                PageRequest.of(0, 20)
        );
        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
        assertThat(page.getContent()).isNotNull();
    }

    @Test
    @DisplayName("Scenario B: from null, to present")
    void testB_fromNull_toPresent() {
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        Page<AiLogEntity> page = aiLogRepository.findByFilters(
                null,
                null,
                null,
                to,
                PageRequest.of(0, 10)
        );
        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Scenario C: from present, to null")
    void testC_fromPresent_toNull() {
        Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
        Page<AiLogEntity> page = aiLogRepository.findByFilters(
                null,
                null,
                from,
                null,
                PageRequest.of(0, 10)
        );
        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Scenario D: from and to present")
    void testD_fromAndToPresent() {
        Instant from = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        Page<AiLogEntity> page = aiLogRepository.findByFilters(
                null,
                null,
                from,
                to,
                PageRequest.of(0, 10)
        );
        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Scenario E: valid range with no matches")
    void testE_validRangeWithNoMatches() {
        Instant from = Instant.parse("2000-01-01T00:00:00Z");
        Instant to = Instant.parse("2000-01-02T00:00:00Z");
        Page<AiLogEntity> page = aiLogRepository.findByFilters(
                null,
                null,
                from,
                to,
                PageRequest.of(0, 10)
        );
        assertThat(page).isNotNull();
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("Scenario F: pagination and totalElements evaluation")
    void testF_paginationAndTotalElements() {
        Page<AiLogEntity> page = aiLogRepository.findByFilters(
                null,
                null,
                null,
                null,
                PageRequest.of(0, 5)
        );
        assertThat(page).isNotNull();
        assertThat(page.getSize()).isEqualTo(5);
        assertThat(page.getNumber()).isEqualTo(0);
        long totalElements = page.getTotalElements(); // Triggers count query
        assertThat(totalElements).isGreaterThanOrEqualTo(page.getContent().size());
        assertThat(page.getTotalPages()).isGreaterThanOrEqualTo(0);
    }
}
