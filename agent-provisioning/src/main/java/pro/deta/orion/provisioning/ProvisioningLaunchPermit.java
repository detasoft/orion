package pro.deta.orion.provisioning;

import java.util.Arrays;

public final class ProvisioningLaunchPermit implements AutoCloseable {
    private static final int MAX_BYTES = 4096;
    private final byte[] bytes;
    private boolean closed;

    public ProvisioningLaunchPermit(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("AgentD launch permit length is invalid");
        }
        for (byte value : bytes) {
            if (value == 0 || value == '\r' || value == '\n') {
                throw new IllegalArgumentException("AgentD launch permit contains an invalid byte");
            }
        }
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    public synchronized byte[] copyBytes() {
        if (closed) {
            throw new IllegalStateException("AgentD launch permit is closed");
        }
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(bytes, (byte) 0);
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "ProvisioningLaunchPermit[redacted]";
    }
}
