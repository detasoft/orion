package pro.deta.orion.agentd.core;

import java.util.Arrays;
import java.util.Objects;

import pro.deta.orion.agent.protocol.AgentAuthentication;

public final class ReconnectToken implements AutoCloseable {
    private final byte[] value;

    public ReconnectToken(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length < AgentAuthentication.MIN_CREDENTIAL_BYTES
                || value.length > AgentAuthentication.MAX_CREDENTIAL_BYTES) {
            throw new IllegalArgumentException("Reconnect token has an invalid length");
        }
        this.value = value.clone();
    }

    public byte[] copyBytes() {
        return value.clone();
    }

    @Override
    public void close() {
        Arrays.fill(value, (byte) 0);
    }

    @Override
    public String toString() {
        return "ReconnectToken[REDACTED]";
    }
}
