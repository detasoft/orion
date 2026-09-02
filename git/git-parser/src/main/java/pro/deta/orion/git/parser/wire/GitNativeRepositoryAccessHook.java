package pro.deta.orion.git.parser.wire;

import java.util.List;

public interface GitNativeRepositoryAccessHook {
    GitNativeRepositoryAccessHook ALLOW_ALL = new GitNativeRepositoryAccessHook() {
    };

    default void beforeReceive(String repositoryName) {
    }

    default void beforeRead(String repositoryName) {
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
