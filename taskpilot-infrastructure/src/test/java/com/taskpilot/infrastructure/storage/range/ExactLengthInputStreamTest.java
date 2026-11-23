package com.taskpilot.infrastructure.storage.range;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;

class ExactLengthInputStreamTest {
    @Test
    void copiesExactBodyAndClosesProvider() throws Exception {
        var delegate = org.mockito.Mockito.spy(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        try (var body = new ExactLengthInputStream(delegate, 3)) {
            assertThat(body.readAllBytes()).containsExactly(1, 2, 3);
        }
        org.mockito.Mockito.verify(delegate).close();
    }

    @Test
    void rejectsTruncatedBody() {
        var body = new ExactLengthInputStream(new ByteArrayInputStream(new byte[] {1, 2}), 3);
        assertThatThrownBy(body::readAllBytes).isInstanceOf(IOException.class).hasMessageContaining("truncated");
    }

    @Test
    void rejectsOversizedBodyWithoutEmittingExcess() {
        var body = new ExactLengthInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2);
        var output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> body.transferTo(output)).isInstanceOf(IOException.class).hasMessageContaining("oversized");
        assertThat(output.toByteArray()).containsExactly(1, 2);
    }
}
