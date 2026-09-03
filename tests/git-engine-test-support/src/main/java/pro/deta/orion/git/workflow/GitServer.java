package pro.deta.orion.git.workflow;

import java.nio.file.Path;
import java.util.Set;

public interface GitServer extends AutoCloseable {
    String name();

    default GitEngine engine() {
        return new GitEngine(name());
    }

    Set<GitCapability> capabilities();

    default String diagnostics() {
        return name();
    }

    GitRemoteRepository createRemoteRepository(Path directory, String repositoryName) throws Exception;

    default GitRemoteRepository missingRemoteRepository(
            Path directory,
            String repositoryName) throws Exception {
        throw new UnsupportedOperationException(
                "Git server does not support push-created repositories: " + name());
    }

    default RepositorySnapshot snapshot(GitRemoteRepository remote) throws Exception {
        return RepositorySnapshot.capture(remote.directory());
    }

    @Override
    default void close() throws Exception {
    }
}
