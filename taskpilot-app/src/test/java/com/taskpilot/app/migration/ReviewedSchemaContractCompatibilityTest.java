package com.taskpilot.app.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewedSchemaContractCompatibilityTest {
    private String sourceCatalog() throws Exception {
        try (var in=getClass().getResourceAsStream("/db/contracts/pg17-source-structure.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Connection catalogConnection(String catalog) throws Exception {
        var c=mock(Connection.class); var s=mock(Statement.class); var r=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s);
        when(s.executeQuery(anyString())).thenReturn(r);
        when(r.next()).thenReturn(true);
        when(r.getString(1)).thenReturn(catalog);
        return c;
    }

    @Test void acceptsRecordedSourceCatalogAfterPostgres17DumpRestore() throws Exception {
        try(var c=catalogConnection(sourceCatalog())) {
            assertThatCode(() -> ReviewedSchemaContract.verify(c)).doesNotThrowAnyException();
        }
    }

    @Test void stillRejectsChangedQueueIndexColumns() throws Exception {
        try(var c=catalogConnection(sourceCatalog().replace(
                "(status, next_attempt_at, lease_until, created_at)", "(status, next_attempt_at, created_at)"))) {
            assertThatThrownBy(() -> ReviewedSchemaContract.verify(c))
                    .isInstanceOf(SecurityException.class).hasMessage("REVIEWED_SCHEMA_MISMATCH");
        }
    }

    @Test void stillRejectsChangedQueueIndexPredicate() throws Exception {
        try(var c=catalogConnection(sourceCatalog().replace("'QUEUED'::character varying", "'CANCELLED'::character varying"))) {
            assertThatThrownBy(() -> ReviewedSchemaContract.verify(c))
                    .isInstanceOf(SecurityException.class).hasMessage("REVIEWED_SCHEMA_MISMATCH");
        }
    }
}
