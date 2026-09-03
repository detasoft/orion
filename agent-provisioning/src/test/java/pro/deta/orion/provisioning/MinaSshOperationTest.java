package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinaSshOperationTest {
    @Test
    void verifiesHostAuthenticatesAndExchangesCommandInput(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        try (TestSshServer server = TestSshServer.start(root, host, client);
             MinaSshOperation operation = MinaSshOperation.open(
                     server.endpoint(), new SshCredentials(client), options())) {
            RemoteCommandResult result = operation.execute(
                    "read value; printf 'out:%s' \"$value\"; printf 'warning' >&2",
                    "payload\n".getBytes(StandardCharsets.UTF_8));

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdoutText()).isEqualTo("out:payload");
            assertThat(result.stderrText()).isEqualTo("warning");
        }
    }

    @Test
    void rejectsAChangedHostKeyBeforeRunningCommands(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        try (TestSshServer server = TestSshServer.start(root, host, client)) {
            SshEndpoint wrong = new SshEndpoint(
                    server.endpoint().host(), server.endpoint().port(),
                    server.endpoint().username(), keyPair().getPublic());

            assertThatThrownBy(() -> MinaSshOperation.open(
                    wrong, new SshCredentials(client), options()))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.HOST_IDENTITY);
        }
    }

    @Test
    void classifiesAuthenticationFailure(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        try (TestSshServer server = TestSshServer.start(root, host, keyPair())) {
            assertThatThrownBy(() -> MinaSshOperation.open(
                    server.endpoint(), new SshCredentials(keyPair()), options()))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.AUTHENTICATION);
        }
    }

    @Test
    void ignoresFallbackClientIdentityAndUserSshConfiguration(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair fallback = keyPair();
        KeyPair selected = keyPair();
        AtomicReference<SshClient> createdClient = new AtomicReference<>();
        try (TestSshServer server = TestSshServer.start(root, host, fallback)) {
            assertThatThrownBy(() -> MinaSshOperation.open(
                    server.endpoint(),
                    new SshCredentials(selected),
                    options(),
                    () -> {
                        SshClient client = SshClient.setUpDefaultClient();
                        client.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(fallback));
                        client.setHostConfigEntryResolver(
                                (hostName, port, local, username, proxy, context) -> null);
                        createdClient.set(client);
                        return client;
                    }))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.AUTHENTICATION);
        }

        SshClient client = createdClient.get();
        assertThat(client.getKeyIdentityProvider()).isSameAs(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        assertThat(client.getHostConfigEntryResolver()).isSameAs(HostConfigEntryResolver.EMPTY);
        assertThat(client.getUserAuthFactories()).containsExactly(UserAuthPublicKeyFactory.INSTANCE);
    }

    @Test
    void boundsCapturedOutput(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        try (TestSshServer server = TestSshServer.start(root, host, client);
             MinaSshOperation operation = MinaSshOperation.open(
                     server.endpoint(), new SshCredentials(client), options())) {
            RemoteCommandResult result = operation.execute(
                    "dd if=/dev/zero bs=20000 count=1 2>/dev/null | tr '\\000' x", new byte[0]);

            assertThat(result.stdout().length).isEqualTo(16 * 1024);
            assertThat(result.stdoutTruncated()).isTrue();
        }
    }

    @Test
    void wholeOperationWatchdogClosesAStalledSession(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        ProvisioningOptions shortOperation = new ProvisioningOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(5), Duration.ofMillis(100));
        try (TestSshServer server = TestSshServer.start(root, host, client);
             MinaSshOperation operation = MinaSshOperation.open(
                     server.endpoint(), new SshCredentials(client), shortOperation)) {
            assertThatThrownBy(() -> operation.execute("sleep 5", new byte[0]))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.TIMEOUT);
        }
    }

    @Test
    void nativeAuthenticationDeadlineMapsToTimeout(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        ProvisioningOptions nativeTimeout = new ProvisioningOptions(
                Duration.ofSeconds(1), Duration.ofMillis(50),
                Duration.ofSeconds(1), Duration.ofSeconds(5));
        try (TestSshServer server = TestSshServer.start(
                root, host, client, Duration.ofMillis(250), Duration.ZERO)) {
            assertThatThrownBy(() -> MinaSshOperation.open(
                    server.endpoint(), new SshCredentials(client), nativeTimeout))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.TIMEOUT);
        }
    }

    @Test
    void nativeChannelOpenDeadlineMapsToTimeout(@TempDir Path root) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        ProvisioningOptions nativeTimeout = new ProvisioningOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMillis(50), Duration.ofSeconds(5));
        try (TestSshServer server = TestSshServer.start(
                root, host, client, Duration.ZERO, Duration.ofMillis(250));
             MinaSshOperation operation = MinaSshOperation.open(
                     server.endpoint(), new SshCredentials(client), nativeTimeout)) {
            assertThatThrownBy(() -> operation.execute("printf complete", new byte[0]))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.TIMEOUT);
        }
    }

    private static ProvisioningOptions options() {
        return new ProvisioningOptions(
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
