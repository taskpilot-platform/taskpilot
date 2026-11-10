package com.taskpilot.app.migration;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** One sealed migration policy, shared by application startup and operator CLI. */
@Configuration
public class MigrationDatabaseConfiguration {
    public static final String HISTORY_SCHEMA="taskpilot_migrations";
    private static final String INIT_SQL="""
        DO $private_history$
        DECLARE client_role text;
        BEGIN
            IF EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='taskpilot_migrations'
                      AND nspowner<>(SELECT oid FROM pg_roles WHERE rolname=current_user)) THEN
                RAISE EXCEPTION 'Unexpected migration schema owner';
            END IF;
            CREATE SCHEMA IF NOT EXISTS taskpilot_migrations;
            REVOKE ALL ON SCHEMA taskpilot_migrations FROM PUBLIC;
            FOREACH client_role IN ARRAY ARRAY['anon','authenticated'] LOOP
                IF to_regrole(client_role) IS NOT NULL THEN
                    EXECUTE format('REVOKE ALL ON SCHEMA taskpilot_migrations FROM %I',client_role);
                    IF has_schema_privilege(client_role,'taskpilot_migrations','USAGE')
                       OR has_schema_privilege(client_role,'taskpilot_migrations','CREATE') THEN
                        RAISE EXCEPTION 'Client role can access migration schema';
                    END IF;
                END IF;
            END LOOP;
        END $private_history$;
        SET statement_timeout='30s';
        """;

    public static String migrationUrl(String jdbcUrl) {
        if (jdbcUrl==null) return null;
        var uri=java.net.URI.create(jdbcUrl.replaceFirst("^jdbc:",""));
        if (uri.getHost()!=null && uri.getHost().endsWith(".pooler.supabase.com") && uri.getPort()==6543)
            return jdbcUrl.replaceFirst(":6543(?=/)",":5432");
        return jdbcUrl;
    }
    public static FluentConfiguration configure(FluentConfiguration config) {
        return config.defaultSchema(HISTORY_SCHEMA).schemas(HISTORY_SCHEMA,"public")
                .table("flyway_schema_history_v2").locations("classpath:db/reconciled")
                .baselineVersion("41").baselineOnMigrate(false).validateOnMigrate(true)
                .outOfOrder(false).cleanDisabled(true).failOnMissingLocations(true)
                .skipDefaultCallbacks(true).initSql(INIT_SQL);
    }
    @Bean
    FlywayConfigurationCustomizer reviewedMigrationConfiguration(Environment env) {
        return config -> {
            String locations=env.getProperty("spring.flyway.locations",env.getProperty("spring.flyway.locations[0]"));
            if ((locations!=null && !"classpath:db/reconciled".equals(locations.trim()))
                    || env.getProperty("spring.flyway.locations[1]")!=null)
                throw new IllegalStateException("UNREVIEWED_MIGRATION_CONFIGURATION: only the packaged bundle is supported");
            String url=env.getProperty("spring.flyway.url");
            if (url==null || url.isBlank()) url=env.getProperty("FLYWAY_DB_URL",env.getProperty("spring.datasource.url"));
            // Keep the proven application endpoint; only the adoption CLI
            // requires a session connection for its bounded write freeze.
            if (url!=null && !url.isBlank()) config.dataSource(url,
                    env.getProperty("spring.flyway.user",env.getProperty("spring.datasource.username")),
                    env.getProperty("spring.flyway.password",env.getProperty("spring.datasource.password")));
            configure(config);
        };
    }
    @Bean
    FlywayMigrationStrategy verifiedMigrationStrategy() {
        return flyway -> {
            try {
                RecoveryAdoption.verifyBeforeMigrate(flyway);
                flyway.migrate();
                RecoveryAdoption.validateComplete(flyway);
            } catch (Exception e) { throw new IllegalStateException("Reviewed Flyway migration failed",e); }
        };
    }
}
