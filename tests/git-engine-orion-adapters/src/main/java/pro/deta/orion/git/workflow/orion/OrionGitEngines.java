package pro.deta.orion.git.workflow.orion;

import org.apache.sshd.client.SshClient;
import pro.deta.orion.git.client.GitClientTransportException;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.client.GitCredentials;
import pro.deta.orion.git.client.GitSmartHttpClientTransport;
import pro.deta.orion.git.client.GitSshClientTransport;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.GitCapability;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

public final class OrionGitEngines {
    static final Set<GitCapability> CLIENT_CAPABILITIES = GitCapability.symmetric();
    static final Set<GitCapability> SERVER_CAPABILITIES = serverCapabilities();

    private OrionGitEngines() {
    }

    public static GitClient client() {
        return new OrionGitClient();
    }

    public static GitServer server() {
        return new OrionGitServer();
    }

    public static GitServer httpServer() {
        return new OrionGitServer(GitTransportScheme.HTTP);
    }

    public static GitServer sshServer() {
        return new OrionGitServer(GitTransportScheme.SSH);
    }

    public static GitClient httpClient() {
        return new OrionGitClient(new GitSmartHttpClientTransport(
                null, GitCredentials.none(), true));
    }

    public static GitClient sshClient() {
        return new OrionGitClient((service, uri, options) -> {
            SshClient client = SshClient.setUpDefaultClient();
            client.setServerKeyVerifier((session, address, key) -> true);
            client.start();
            try {
                GitClientTransportSession delegate =
                        new GitSshClientTransport(
                                client, GitCredentials.none()).open(service, uri, options);
                return new GitClientTransportSession() {
                    @Override
                    public BufferedByteInputV2 input() {
                        return delegate.input();
                    }

                    @Override
                    public BufferedByteOutput output() {
                        return delegate.output();
                    }

                    @Override
                    public void close() throws IOException {
                        try {
                            delegate.close();
                        } finally {
                            client.stop();
                        }
                    }
                };
            } catch (GitClientTransportException | RuntimeException failure) {
                client.stop();
                throw failure;
            }
        });
    }

    private static Set<GitCapability> serverCapabilities() {
        Set<GitCapability> capabilities = EnumSet.copyOf(GitCapability.symmetric());
        capabilities.add(GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH);
        return Set.copyOf(capabilities);
    }
}
