package pro.deta.orion.git.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
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

    default GitRepositoryFileSnapshot loadFiles(String branch, List<String> paths) throws IOException, GitOperationException {
        throw new GitOperationException("Repository " + name() + " does not support file loading");
    }

    default void saveFiles(String branch, Map<String, byte[]> files, String message, GitCommitAuthor author) throws IOException, GitOperationException {
        throw new GitOperationException("Repository " + name() + " does not support file saving");
    }

    <T> Optional<T> unwrap(Class<T> repositoryType);

    default <T> T unwrapOrThrow(Class<T> repositoryType) {
        return unwrap(repositoryType).orElseThrow(() ->
                new IllegalStateException("Repository " + name() + " cannot be used as " + repositoryType.getName()));
    }

    @Override
    void close();
}
