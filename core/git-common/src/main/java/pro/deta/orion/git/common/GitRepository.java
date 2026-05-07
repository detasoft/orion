package pro.deta.orion.git.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Backend-independent repository handle used by Orion services. It is the boundary between server logic and the
 * concrete repository implementation, so callers should depend on this type instead of JGit's Repository directly.
 */
public interface GitRepository extends AutoCloseable {
    String name();

    String description();

    GitRepository withFetchAccessCheck(Consumer<GitFetchAccessRequest> fetchAccessCheck);

    void upload(GitUploadRequest request, InputStream input, OutputStream output, OutputStream error) throws IOException, GitOperationException;

    void receive(GitReceiveRequest request, InputStream input, OutputStream output, OutputStream error) throws IOException, GitOperationException;

    <T> Optional<T> unwrap(Class<T> repositoryType);

    default <T> T unwrapOrThrow(Class<T> repositoryType) {
        return unwrap(repositoryType).orElseThrow(() ->
                new IllegalStateException("Repository " + name() + " cannot be used as " + repositoryType.getName()));
    }

    @Override
    void close();
}
