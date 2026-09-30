package pro.deta.orion.test;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.test.integration.OrionTestRootAccess;
import pro.deta.orion.util.KeyUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PlaywrightTransportTest {
    @TempDir
    Path tempDir;

    @Test
    void browserServerStartsAllTransportsAndServesNativeGit() throws Exception {
        OrionConfiguration configuration = configuration();
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            KeyPair rootKey = KeyUtils.generateRSAKeyPair().valueOrFailure("test root key");
            TestBearerTokens.enrollRootKey(orion.accessControlService(), rootKey);
            String token = OrionTestRootAccess.issueToken(
                    orion.accessControlService(), rootKey.getPublic(), 600);
            RuntimeHttpTestSupport.HttpResponse state = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/lifecycle/state"), TestBearerTokens.bearer(token));
            assertThat(state.status()).isEqualTo(200);
            assertThat(state.body()).contains("git-native: RUNNING", "git-ssh: RUNNING", "http: RUNNING");

            orion.repositoryProvider().create("transport-check")
                    .valueOrFailure("test Git repository")
                    .files().withAccess("refs/heads/main", "seed transport check", new GitCommitAuthor("Test",
                            "test@orion.test"), fileAccess -> {
                fileAccess.write("README.md", "transport check\n".getBytes(StandardCharsets.UTF_8));
                fileAccess.apply();
                return null;
            });
            String gitUrl = "git://127.0.0.1:" + orion.gitPort();
            assertThat(Git.lsRemoteRepository().setRemote(gitUrl + "/transport-check.git")
                    .setTimeout(10).call())
                    .anySatisfy(ref -> assertThat(ref.getName()).isEqualTo("refs/heads/main"));
            try (SshClient client = sshClient(orion)) {
                assertThat(sshState(client, rootKey, orion)).isEqualTo(state.body().stripTrailing());
            }
        }
    }

    @Test
    void browserServerRejectsUnknownSshKeysAndStillAcceptsItsAdminKey() throws Exception {
        OrionConfiguration configuration = configuration();
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            KeyPair rootKey = KeyUtils.generateRSAKeyPair().valueOrFailure("test root key");
            TestBearerTokens.enrollRootKey(orion.accessControlService(), rootKey);
            try (SshClient client = sshClient(orion)) {
                KeyPair unknownKey = KeyUtils.generateRSAKeyPair().valueOrFailure("unknown test key");
                try (ClientSession session = client.connect("root", "127.0.0.1",
                                orion.sshPort())
                        .verify(10, TimeUnit.SECONDS).getSession()) {
                    session.addPublicKeyIdentity(unknownKey);
                    assertThatThrownBy(() -> session.auth().verify(10, TimeUnit.SECONDS))
                            .isInstanceOf(IOException.class)
                            .hasMessageContaining("No more authentication methods available");
                    assertThat(session.isAuthenticated()).isFalse();
                }
                assertThat(sshState(client, rootKey, orion)).contains("git-ssh: RUNNING");
            }
        }
    }

    private OrionConfiguration configuration() throws Exception {
        OrionConfiguration configuration = PlaywrightExternalServicesIT.serverConfiguration(
                tempDir.resolve("orion"));
        TestPorts.configure(configuration);
        return configuration;
    }

    private static SshClient sshClient(RuntimeHttpTestSupport.StartedOrion orion) throws Exception {
        List<KeyPair> hostKeys = orion.identity().sshHostKeys().keyPairs();
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((session, address, serverKey) -> {
            for (KeyPair expected : hostKeys) {
                if (org.apache.sshd.common.config.keys.KeyUtils.compareKeys(expected.getPublic(), serverKey)) {
                    return true;
                }
            }
            return false;
        });
        client.start();
        return client;
    }

    private static String sshState(
            SshClient client, KeyPair rootKey, RuntimeHttpTestSupport.StartedOrion orion) throws Exception {
        try (ClientSession session = client.connect(
                        "root", "127.0.0.1", orion.sshPort())
                .verify(10, TimeUnit.SECONDS).getSession()) {
            session.addPublicKeyIdentity(rootKey);
            session.auth().verify(10, TimeUnit.SECONDS);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            try (ChannelExec channel = session.createExecChannel("state")) {
                channel.setOut(output);
                channel.setErr(error);
                channel.open().verify(10, TimeUnit.SECONDS);
                assertThat(channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 10_000))
                        .contains(ClientChannelEvent.CLOSED);
                assertThat(channel.getExitStatus()).isZero();
                assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
            }
            return output.toString(StandardCharsets.UTF_8).stripTrailing();
        }
    }
}
