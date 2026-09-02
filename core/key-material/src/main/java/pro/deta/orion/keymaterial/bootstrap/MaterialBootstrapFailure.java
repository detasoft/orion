package pro.deta.orion.keymaterial.bootstrap;

import java.util.Objects;

/**
 * Typed, diagnostics-safe failure produced while assembling one material and configuration snapshot pair.
 */
public record MaterialBootstrapFailure(
        Source source,
        Stage stage,
        Code code,
        String message,
        Throwable cause) {

    public MaterialBootstrapFailure(Source source, Stage stage, Code code, String message) {
        this(source, stage, code, message, null);
    }

    public MaterialBootstrapFailure {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Material bootstrap failure message must not be empty");
        }
    }

    public enum Source {
        MATERIAL_STORE,
        CONFIGURATION_SNAPSHOT,
        INPUT_PAIR,
        RUNTIME
    }

    public enum Stage {
        LOAD,
        PREPARE,
        PUBLISH
    }

    public enum Code {
        MISSING,
        UNAVAILABLE,
        CORRUPT,
        INCOMPATIBLE,
        MISMATCHED,
        ACTIVATION_FAILED,
        INTERNAL
    }
}
