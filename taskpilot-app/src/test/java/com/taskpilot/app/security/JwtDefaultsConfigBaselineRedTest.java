package com.taskpilot.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Baseline RED test asserting application.yml JWT issuer & audience defaults alongside 15m TTL.
 */
class JwtDefaultsConfigBaselineRedTest {

    @Test
    void applicationYml_mustDefaultJwtAccessTtlTo15MinutesAndSetIssuerAudienceDefaults() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();

        assertThat(props).isNotNull();

        String rawExpiration = props.getProperty("application.security.jwt.expiration");
        String defaultExpiration = extractPlaceholderDefault(rawExpiration);
        assertThat(defaultExpiration)
                .as("Default JWT access expiration in application.yml must be configured and <= 900000 ms (15m)")
                .isNotBlank();
        assertThat(Long.parseLong(defaultExpiration))
                .isPositive()
                .isLessThanOrEqualTo(900_000L);

        assertThat(extractPlaceholderDefault(props.getProperty("application.security.jwt.issuer")))
                .as("Default JWT issuer must be 'taskpilot'")
                .isEqualTo("taskpilot");

        assertThat(extractPlaceholderDefault(props.getProperty("application.security.jwt.audience")))
                .as("Default JWT audience must be 'taskpilot-api'")
                .isEqualTo("taskpilot-api");
    }

    private static String extractPlaceholderDefault(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) {
                return null;
            }
            return trimmed.substring(colonIdx + 1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
