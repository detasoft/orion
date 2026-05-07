package pro.deta.orion.git.common;

import java.util.Optional;

/**
 * Backend-independent repository handle used by Orion services. It is the boundary between server logic and the
 * concrete repository implementation, so callers should depend on this type instead of JGit's Repository directly.
 */
public interface GitRepository extends AutoCloseable {
    String name();

    String description();

    <T> Optional<T> unwrap(Class<T> repositoryType);

    default <T> T unwrapOrThrow(Class<T> repositoryType) {
        return unwrap(repositoryType).orElseThrow(() ->
                new IllegalStateException("Repository " + name() + " cannot be used as " + repositoryType.getName()));
    }

    @Override
    void close();
}
