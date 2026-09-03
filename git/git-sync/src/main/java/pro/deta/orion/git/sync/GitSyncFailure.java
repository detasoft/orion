package pro.deta.orion.git.sync;

import java.util.Objects;

public record GitSyncFailure(
        Kind kind,
        boolean retryable) {
    public GitSyncFailure {
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        CONFIGURATION,
        CREDENTIAL,
        AUTHORIZATION,
        HOST_VERIFICATION,
        TRANSPORT,
        PROTOCOL,
        REMOTE_REJECTION,
        LOCAL_PUBLICATION,
        DIVERGENCE
    }
}
