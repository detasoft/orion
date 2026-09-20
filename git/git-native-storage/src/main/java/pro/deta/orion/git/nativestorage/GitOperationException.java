package pro.deta.orion.git.nativestorage;

import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import java.util.List;

public class GitOperationException extends Exception {
    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
    public static void requireSuccess(List<RefUpdateResult> results) throws GitOperationException {
        for (RefUpdateResult result : results) {
            if (result.status() == RefUpdateResult.Status.EXPECTED_OLD_MISMATCH) {
                throw new GitRepositoryConcurrentUpdateException("Cannot update Git repository: stale ref");
            }
        }
        for (RefUpdateResult result : results) {
            if (result.status() != RefUpdateResult.Status.APPLIED) {
                throw new GitOperationException("Cannot update Git ref " + result.update().ref() + ": "
                        + result.message().orElse(result.status().name()));
            }
        }
    }
}
