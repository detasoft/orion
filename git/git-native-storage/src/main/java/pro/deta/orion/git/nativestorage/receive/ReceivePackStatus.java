package pro.deta.orion.git.nativestorage.receive;

import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.GitRepositoryConcurrentUpdateException;

import java.util.List;
import java.util.Objects;

public record ReceivePackStatus(String refName, boolean ok, String message) {
    public ReceivePackStatus {
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(message, "message");
    }

    public static void requireSuccess(List<ReceivePackStatus> statuses) throws GitOperationException {
        for (ReceivePackStatus status : statuses) {
            if (!status.ok() && "stale".equals(status.message())) {
                throw new GitRepositoryConcurrentUpdateException("Cannot update Git repository: stale ref");
            }
        }
        for (ReceivePackStatus status : statuses) {
            if (!status.ok()) {
                throw new GitOperationException("Cannot update Git ref " + status.refName() + ": " + status.message());
            }
        }
    }
}
