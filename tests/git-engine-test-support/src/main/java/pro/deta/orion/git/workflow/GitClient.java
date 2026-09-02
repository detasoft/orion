package pro.deta.orion.git.workflow;

import java.nio.file.Path;
import java.util.Set;

public interface GitClient {
    String name();

    default GitEngine engine() {
        return new GitEngine(name());
    }

    default Set<GitCapability> capabilities() {
        return GitCapability.all();
    }

    boolean available();

    default void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException("Git client prerequisite is unavailable: " + name());
        }
    }

    default String diagnostics() {
        return name();
    }

    GitWorkTree init(Path directory) throws Exception;

    GitWorkTree clone(String remoteUri, Path directory) throws Exception;

    default GitWorkTree clone(GitRemoteRepository remote, Path directory) throws Exception {
        return clone(remote.uri(), directory);
    }
}
