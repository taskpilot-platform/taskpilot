package com.taskpilot.app.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigBaselineDefaultsTest {

    private static final String KNOWN_PUBLIC_SAMPLE_JWT_KEY =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    @Test
    void commonApplicationYmlRemovesPublicSampleJwtFallbackAndSetsFifteenMinuteTtl() throws Exception {
        PropertySource<?> config = new YamlPropertySourceLoader()
                .load("default", new ClassPathResource("application.yml"))
                .getFirst();

        Object rawSecret = config.getProperty("application.security.jwt.secret-key");
        Object rawExpiration = config.getProperty("application.security.jwt.expiration");

        assertThat(String.valueOf(rawSecret)).doesNotContain(KNOWN_PUBLIC_SAMPLE_JWT_KEY);
        assertThat(String.valueOf(rawExpiration)).contains("900000");
    }

    @Test
    void commonApplicationYmlAndSpyPropertiesDisableP6SpyByDefaultAndFixFormatterPackage() throws Exception {
        PropertySource<?> config = new YamlPropertySourceLoader()
                .load("default", new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(String.valueOf(config.getProperty("decorator.datasource.p6spy.enable-logging")))
                .contains("false");
        assertThat(config.getProperty("decorator.datasource.p6spy.custom-format"))
                .isEqualTo("com.taskpilot.infrastructure.util.P6SpySqlFormatter");

        Properties spyProps = new Properties();
        try (var in = new ClassPathResource("spy.properties").getInputStream()) {
            spyProps.load(in);
        }
        assertThat(spyProps.getProperty("logMessageFormat"))
                .isEqualTo("com.taskpilot.infrastructure.util.P6SpySqlFormatter");
    }

    @Test
    void devProfileUsesExplicitLocalOriginsInsteadOfWildcardPort() throws Exception {
        PropertySource<?> devConfig = new YamlPropertySourceLoader()
                .load("dev", new ClassPathResource("application-dev.yml"))
                .getFirst();

        String origins = String.valueOf(devConfig.getProperty("app.cors.allowed-origins"));
        assertThat(origins).doesNotContain("*");
    }
}
