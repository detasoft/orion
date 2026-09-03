package pro.deta.orion.git.workflow.orion;

import pro.deta.orion.git.client.GitClientFailure;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientResult;
import pro.deta.orion.git.client.GitReceivePackClient;
import pro.deta.orion.git.client.GitReceivePackResult;
import pro.deta.orion.git.client.GitTcpClientTransport;
import pro.deta.orion.git.client.GitUploadPackClient;
import pro.deta.orion.git.workflow.GitCapability;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitWorkTree;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

final class OrionGitClient implements GitClient {
    private final GitClientOptions options = GitClientOptions.defaults();
    private final GitUploadPackClient uploadPack = new GitUploadPackClient(new GitTcpClientTransport());
    private final GitReceivePackClient receivePack = new GitReceivePackClient(new GitTcpClientTransport());

    @Override
    public String name() {
        return "orion";
    }

    @Override
    public Set<GitCapability> capabilities() {
        return OrionGitEngines.CLIENT_CAPABILITIES;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public GitWorkTree init(Path directory) throws Exception {
        return OrionGitWorkTree.create(this, directory);
    }

    @Override
    public GitWorkTree clone(String remoteUri, Path directory) throws Exception {
        OrionGitWorkTree workTree = OrionGitWorkTree.create(this, directory);
        try {
            workTree.addRemote("origin", new GitRemoteRepository(directory, remoteUri));
            workTree.fetch("origin");
            workTree.updateRef("refs/heads/main", "refs/remotes/origin/main");
            return workTree;
        } catch (Exception | Error failure) {
            workTree.close();
            throw failure;
        }
    }

    GitUploadPackClient uploadPack() {
        return uploadPack;
    }

    GitReceivePackClient receivePack() {
        return receivePack;
    }

    GitClientOptions options() {
        return options;
    }

    URI uri(GitRemoteRepository remote) {
        return URI.create(remote.uri());
    }

    static <T> T requireSuccess(GitClientResult<T> result, String operation) {
        if (result instanceof GitClientResult.Success<T> success) {
            return success.value();
        }
        GitClientFailure failure = ((GitClientResult.Failed<T>) result).failure();
        throw new IllegalStateException(
                "Orion " + operation + " failed during " + failure.phase()
                        + " (" + failure.kind() + "): " + failure.message(),
                failure.cause());
    }

    static void requireAccepted(GitReceivePackResult result) {
        if (!result.accepted()) {
            throw new IllegalStateException(
                    "Orion push was rejected: unpack=" + result.unpackStatus() + ", refs=" + result.refs());
        }
    }
}
