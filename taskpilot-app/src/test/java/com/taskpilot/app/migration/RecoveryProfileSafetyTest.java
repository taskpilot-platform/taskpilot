package com.taskpilot.app.migration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryProfileSafetyTest {
    @Test
    void recoveryIsAnAliasForThePackagedPolicyAndNeverImplicitlyBaselinesOrCleans() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("recovery", new ClassPathResource("application-recovery.yml")).getFirst();
        assertThat(source.getProperty("spring.config.activate.on-profile")).isEqualTo("recovery");
        assertThat(source.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
        assertThat(source.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
        assertThat(source.getProperty("spring.flyway.out-of-order")).isEqualTo(false);
        assertThat(source.getProperty("spring.flyway.clean-disabled")).isEqualTo(true);
        assertThat(source.getProperty("spring.flyway.table")).isEqualTo("flyway_schema_history_v2");
        assertThat(source.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/reconciled");
        assertThat(source.getProperty("spring.flyway.default-schema")).isEqualTo("taskpilot_migrations");
        assertThat(source.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void effectivePolicyRejectsAnExternalBundleInsteadOfSilentlyIgnoringIt() {
        var env=new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.flyway.locations","filesystem:/not-the-reviewed-bundle");
        var customizer=new MigrationDatabaseConfiguration().reviewedMigrationConfiguration(env);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> customizer.customize(org.flywaydb.core.Flyway.configure())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UNREVIEWED_MIGRATION_CONFIGURATION");
    }

    @Test
    void effectiveAliasUsesPrivateHistoryAndPackagedScriptsWithoutAnExternalBundle() throws Exception {
        var source=new YamlPropertySourceLoader()
                .load("recovery",new ClassPathResource("application-recovery.yml")).getFirst();
        var env=new org.springframework.mock.env.MockEnvironment();
        env.getPropertySources().addFirst(source);
        var config=org.flywaydb.core.Flyway.configure();
        new MigrationDatabaseConfiguration().reviewedMigrationConfiguration(env).customize(config);
        assertThat(config.getDefaultSchema()).isEqualTo("taskpilot_migrations");
        assertThat(config.getTable()).isEqualTo("flyway_schema_history_v2");
        assertThat(config.getLocations()).extracting(org.flywaydb.core.api.Location::getDescriptor)
                .containsExactly("classpath:db/reconciled");
        assertThat(config.isBaselineOnMigrate()).isFalse();
        assertThat(config.isCleanDisabled()).isTrue();
    }

    @Test
    void existingProfileDoesNotAutomaticallySelectRecovery() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("default", new ClassPathResource("application.yml")).getFirst();
        assertThat(source.getProperty("spring.profiles.active")).isEqualTo("${SPRING_PROFILES_ACTIVE:dev}");
        assertThat(source.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/reconciled");
        assertThat(source.getProperty("spring.flyway.table")).isEqualTo("flyway_schema_history_v2");
    }
}
