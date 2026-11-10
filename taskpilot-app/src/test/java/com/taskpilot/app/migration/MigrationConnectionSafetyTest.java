package com.taskpilot.app.migration;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

class MigrationConnectionSafetyTest {
    @Test
    void pooledLoginAndDatabaseRoleAreBoundSeparately() throws Exception {
        String url="jdbc:postgresql://localhost/test";
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)));
        var target=new RecoveryAdoption.Target(url,"postgres.project_ref","unused");
        var approval=new RecoveryAdoption.Approval("postgres","postgres","postgres.project_ref",hash,"catalog","history","data","bundle");
        assertThatCode(() -> RecoveryAdoption.verifyTarget(target,approval)).doesNotThrowAnyException();
        assertThatThrownBy(() -> RecoveryAdoption.verifyTarget(new RecoveryAdoption.Target(url,"other_login","unused"),approval)).isInstanceOf(SecurityException.class);
    }

    @Test
    void onlySupabaseTransactionPoolerIsRewrittenToSessionMode() {
        assertThat(MigrationDatabaseConfiguration.migrationUrl("jdbc:postgresql://aws-0.example.pooler.supabase.com:6543/postgres?sslmode=require&prepareThreshold=0"))
                .isEqualTo("jdbc:postgresql://aws-0.example.pooler.supabase.com:5432/postgres?sslmode=require&prepareThreshold=0");
        assertThat(MigrationDatabaseConfiguration.migrationUrl("jdbc:postgresql://localhost:6543/test")).isEqualTo("jdbc:postgresql://localhost:6543/test");
    }

    @Test
    void applicationKeepsItsWorkingDatabaseEndpoint() {
        String pooled="jdbc:postgresql://aws-0.example.pooler.supabase.com:6543/postgres?prepareThreshold=0";
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("spring.datasource.url",pooled)
                .withProperty("spring.datasource.username","login").withProperty("spring.datasource.password","unused");
        var config=org.mockito.Mockito.spy(org.flywaydb.core.Flyway.configure());
        new MigrationDatabaseConfiguration().reviewedMigrationConfiguration(env).customize(config);
        org.mockito.Mockito.verify(config).dataSource(pooled,"login","unused");
    }
}
