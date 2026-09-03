package pro.deta.orion.git.sync;

import java.time.Instant;
import java.util.Objects;

public record GitOutboundWork(
        String refName,
        String desiredObjectId,
        long sequence,
        int attempt,
        Instant notBefore,
        boolean inFlight) {
    public GitOutboundWork {
        GitBranchPlan.requireHead(refName);
        desiredObjectId = GitBranchPlan.requireObjectId(
                desiredObjectId,
                "desiredObjectId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        Objects.requireNonNull(notBefore, "notBefore");
    }

    static GitOutboundWork pending(
            String refName,
            String desiredObjectId,
            long sequence) {
        return new GitOutboundWork(
                refName,
                desiredObjectId,
                sequence,
                0,
                Instant.EPOCH,
                false);
    }

    GitOutboundWork claim() {
        if (inFlight) {
            throw new IllegalStateException("outbound work is already in flight");
        }
        return new GitOutboundWork(
                refName,
                desiredObjectId,
                sequence,
                attempt + 1,
                notBefore,
                true);
    }

    GitOutboundWork pendingAt(Instant availableAt) {
        return new GitOutboundWork(
                refName,
                desiredObjectId,
                sequence,
                attempt,
                Objects.requireNonNull(availableAt, "availableAt"),
                false);
    }
}
