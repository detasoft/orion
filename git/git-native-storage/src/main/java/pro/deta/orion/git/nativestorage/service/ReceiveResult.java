package pro.deta.orion.git.nativestorage.service;

import java.util.List;
import java.util.Objects;

public record ReceiveResult(
        boolean packAccepted,
        String packError,
        List<RefResult> refResults) {

    public ReceiveResult {
        Objects.requireNonNull(refResults, "refResults");
        refResults = List.copyOf(refResults);
    }

    public static ReceiveResult packFailure(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ReceiveResult(false, reason, List.of());
    }

    public static ReceiveResult success(List<RefResult> refResults) {
        return new ReceiveResult(true, null, refResults);
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
