package pro.deta.orion.agentd.core;

import java.io.IOException;

public final class HandshakeException extends IOException {
    public HandshakeException(String message) {
        super(message);
    }

    public HandshakeException(String message, Throwable cause) {
        super(message, cause);
    }
}
