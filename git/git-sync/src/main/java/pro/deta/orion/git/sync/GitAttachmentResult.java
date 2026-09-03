package pro.deta.orion.git.sync;

import java.util.List;
import java.util.Objects;

public record GitAttachmentResult(
        Status status,
        List<GitSyncConflict> conflicts) {
    public GitAttachmentResult {
        Objects.requireNonNull(status, "status");
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        if (status == Status.ATTACHED && !conflicts.isEmpty()) {
            throw new IllegalArgumentException("an attached result cannot contain conflicts");
        }
        if (status == Status.CONFLICTED && conflicts.isEmpty()) {
            throw new IllegalArgumentException("a conflicted result requires a conflict");
        }
    }

    public boolean active() {
        return status == Status.ATTACHED;
    }

    static GitAttachmentResult attached() {
        return new GitAttachmentResult(Status.ATTACHED, List.of());
    }

    static GitAttachmentResult conflicted(List<GitSyncConflict> conflicts) {
        return new GitAttachmentResult(Status.CONFLICTED, conflicts);
    }

    public enum Status {
        ATTACHED,
        CONFLICTED
    }
}
