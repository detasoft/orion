package pro.deta.orion.git.workflow;

import java.nio.file.Path;
import java.util.Set;

public interface GitServer extends AutoCloseable {
    String name();

    default GitEngine engine() {
        return new GitEngine(name());
    }

    Set<GitCapability> capabilities();

    GitRemoteRepository createRemoteRepository(Path directory, String repositoryName) throws Exception;

    default RepositorySnapshot snapshot(GitRemoteRepository remote) throws Exception {
        return RepositorySnapshot.capture(remote.directory());
    }

    @Override
    default void close() throws Exception {
    }
}
