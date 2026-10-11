package pro.deta.orion.git.proxy;

import java.util.Arrays;

final class BootstrapSecret implements AutoCloseable {
    private final char[] value;
    private boolean closed;

    BootstrapSecret(char[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("Bootstrap secret must not be empty");
        }
        this.value = value.clone();
    }

    synchronized char[] copy() {
        if (closed) {
            throw new IllegalStateException("Bootstrap secret is closed");
        }
        return value.clone();
    }

    @Override
    public synchronized void close() {
        Arrays.fill(value, '\0');
        closed = true;
    }
}
