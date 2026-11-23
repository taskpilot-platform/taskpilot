package com.taskpilot.infrastructure.storage.range;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Enforces the promised partial-body length without buffering or exposing excess bytes. */
public final class ExactLengthInputStream extends InputStream {
    private final InputStream delegate;
    private long remaining;

    public ExactLengthInputStream(InputStream delegate, long length) {
        this.delegate = Objects.requireNonNull(delegate);
        if (length < 0) throw new IllegalArgumentException("Length must be nonnegative");
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        if (remaining == 0) return verifyEnd();
        int value = delegate.read();
        if (value < 0) throw new IOException("Storage provider returned a truncated partial body");
        remaining--;
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (remaining == 0) return verifyEnd();
        int count = delegate.read(bytes, offset, (int) Math.min(length, remaining));
        if (count < 0) throw new IOException("Storage provider returned a truncated partial body");
        remaining -= count;
        return count;
    }

    private int verifyEnd() throws IOException {
        if (delegate.read() >= 0) throw new IOException("Storage provider returned an oversized partial body");
        return -1;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
