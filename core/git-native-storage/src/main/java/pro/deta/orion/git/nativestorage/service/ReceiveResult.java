package pro.deta.orion.git.nativestorage.service;

import pro.deta.orion.git.common.GitRefUpdate;

import java.util.List;
import java.util.Objects;

public record ReceiveResult(
        boolean packAccepted,
        String packError,
        List<RefResult> refResults,
        List<GitRefUpdate> refUpdates) {

    public ReceiveResult {
        Objects.requireNonNull(refResults, "refResults");
        Objects.requireNonNull(refUpdates, "refUpdates");
        refResults = List.copyOf(refResults);
        refUpdates = List.copyOf(refUpdates);
    }

    public static ReceiveResult packFailure(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ReceiveResult(false, reason, List.of(), List.of());
    }

    public static ReceiveResult success(List<RefResult> refResults) {
        return success(refResults, List.of());
    }

    public static ReceiveResult success(List<RefResult> refResults, List<GitRefUpdate> refUpdates) {
        return new ReceiveResult(true, null, refResults, refUpdates);
    }

    public record RefResult(String refName, boolean ok, String reason) {
        public RefResult {
            Objects.requireNonNull(refName, "refName");
        }

        public static RefResult ok(String refName) {
            return new RefResult(refName, true, null);
        }

        public static RefResult ng(String refName, String reason) {
            Objects.requireNonNull(reason, "reason");
            return new RefResult(refName, false, reason);
        }
    }
}
