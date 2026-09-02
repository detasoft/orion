package pro.deta.orion.keymaterial;

import java.security.GeneralSecurityException;

public final class ConfigurationSecretException extends GeneralSecurityException {
    private final Reason reason;

    public ConfigurationSecretException(Reason reason, String message) {
        super(message);
        this.reason = requireReason(reason);
    }

    public ConfigurationSecretException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = requireReason(reason);
    }

    public Reason reason() {
        return reason;
    }

    private static Reason requireReason(Reason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("Configuration secret failure reason must not be null");
        }
        return reason;
    }

    public enum Reason {
        MALFORMED,
        UNSUPPORTED,
        MATERIAL_MISMATCH,
        AUTHENTICATION_FAILED
    }
}
