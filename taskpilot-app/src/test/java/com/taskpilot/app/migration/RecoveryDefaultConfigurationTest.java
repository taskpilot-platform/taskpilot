package com.taskpilot.app.migration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryDefaultConfigurationTest {
    @Test
    void ordinaryStartupValidatesReconciledHistoryAndCannotAdoptOrClean() throws Exception {
        var config = new YamlPropertySourceLoader().load("default", new ClassPathResource("application.yml")).getFirst();
        assertThat(config.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/reconciled");
        assertThat(config.getProperty("spring.flyway.default-schema")).isEqualTo("taskpilot_migrations");
        assertThat(config.getProperty("spring.flyway.table")).isEqualTo("flyway_schema_history_v2");
        assertThat(config.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
        assertThat(config.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
        assertThat(config.getProperty("spring.flyway.out-of-order")).isEqualTo(false);
        assertThat(config.getProperty("spring.flyway.clean-disabled")).isEqualTo(true);
    }
}
