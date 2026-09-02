package pro.deta.orion.git.workflow;

import java.util.Objects;

public record GitOperationResult(
        Status status,
        String diagnostic,
        RepositorySnapshot before,
        RepositorySnapshot after) {
    public enum Status {
        ACCEPTED,
        REJECTED,
        NON_FAST_FORWARD
    }

    public GitOperationResult {
        Objects.requireNonNull(status, "status");
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public GitOperationResult(Status status, String diagnostic) {
        this(status, diagnostic, null, null);
    }

    public static GitOperationResult accepted() {
        return new GitOperationResult(Status.ACCEPTED, "", null, null);
    }

    public static GitOperationResult rejected(String diagnostic) {
        return new GitOperationResult(Status.REJECTED, diagnostic, null, null);
    }

    public static GitOperationResult nonFastForward(String diagnostic) {
        return new GitOperationResult(Status.NON_FAST_FORWARD, diagnostic, null, null);
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public boolean hasSnapshots() {
        return before != null && after != null;
    }

    public boolean stateUnchanged() {
        return hasSnapshots() && before.difference(after) == null;
    }

    GitOperationResult withSnapshots(RepositorySnapshot before, RepositorySnapshot after) {
        return new GitOperationResult(status, diagnostic, before, after);
    }
}
