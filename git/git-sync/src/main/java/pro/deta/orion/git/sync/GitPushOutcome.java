package pro.deta.orion.git.sync;

import java.util.Objects;
import java.util.Optional;

public record GitPushOutcome(
        Status status,
        Optional<String> observedObjectId) {
    public GitPushOutcome {
        Objects.requireNonNull(status, "status");
        observedObjectId = Objects.requireNonNull(
                observedObjectId,
                "observedObjectId");
    }

    public enum Status {
        APPLIED,
        ALREADY_CURRENT,
        REMOTE_CHANGED,
        REJECTED
    }
}
