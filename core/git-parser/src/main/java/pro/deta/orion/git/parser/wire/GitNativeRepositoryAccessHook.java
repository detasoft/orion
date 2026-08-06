package pro.deta.orion.git.parser.wire;

public interface GitNativeRepositoryAccessHook {
    GitNativeRepositoryAccessHook ALLOW_ALL = new GitNativeRepositoryAccessHook() {
    };

    default void beforeReceive(String repositoryName) {
    }

    default void beforeRead(String repositoryName) {
    }

    default void beforeCreate(String repositoryName) {
    }

    default void beforeWrite(String repositoryName) {
    }

    final class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
