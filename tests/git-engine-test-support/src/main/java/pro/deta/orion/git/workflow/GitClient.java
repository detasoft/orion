package pro.deta.orion.git.workflow;

import java.nio.file.Path;

public interface GitClient {
    String name();

    boolean available();

    GitWorkTree init(Path directory) throws Exception;

    GitWorkTree clone(String remoteUri, Path directory) throws Exception;

    default GitWorkTree clone(GitRemoteRepository remote, Path directory) throws Exception {
        return clone(remote.uri(), directory);
    }
}
