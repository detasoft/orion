package pro.deta.orion.git.nativestorage.receive;

import java.util.List;

/**
 * Repository operation checks. Unrestricted read requires access to every branch and is denied by default;
 * authorizing a repository read or a particular fetch does not imply that broader permission.
 */
public interface GitNativeRepositoryAccessHook {
    GitNativeRepositoryAccessHook ALLOW_ALL = new GitNativeRepositoryAccessHook() {
        @Override
        public boolean allowsUnrestrictedRead(String repositoryName) {
            return true;
        }
    };

    default void beforeReceive(String repositoryName) {
    }

    default void beforeRead(String repositoryName) {
    }

    default boolean allowsUnrestrictedRead(String repositoryName) {
        return false;
    }

    default void beforeFetch(
            String repositoryName,
            List<String> branchNames) {
    }

    default void beforeCreate(String repositoryName) {
    }

    default void beforeWrite(String repositoryName) {
    }

    default void beforeUpdate(
            String repositoryName,
            String refName,
            boolean force) {
    }

    final class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
