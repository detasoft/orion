package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.sshd.client.SshClient;
import pro.deta.orion.decision.ConnectionFailureHandler;
import pro.deta.orion.decision.DecisionAnswer;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.schema.orion.PrincipalAddress;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinaSshDecisionTest {
    private static final PrincipalAddress ADMIN = PrincipalAddress.parse("system/admin");

    @Test
    void resumesPublicKeyConnectionWithoutRememberingTrust(@TempDir Path root) throws Exception {
        resumesConnection(root, false);
    }

    @Test
    void resumesPasswordConnectionWithoutRememberingTrust(@TempDir Path root) throws Exception {
        resumesConnection(root, true);
    }

    private void resumesConnection(Path root, boolean passwordAuthentication) throws Exception {
        KeyPair host = keyPair();
        KeyPair client = keyPair();
        try (TestSshServer server = TestSshServer.startWithPassword(root, host, client, "secret");
             java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
             DecisionRegistry registry = new DecisionRegistry(4, Runnable::run, (actor, scope) -> true)) {
            ConnectionFailureHandler handler = new ConnectionFailureHandler(registry);
            SshEndpoint endpoint = new SshEndpoint("127.0.0.1", server.endpoint().port(), "orion", passwordAuthentication ? null : keyPair().getPublic());
            AtomicReference<SshClient> firstClient = new AtomicReference<>();
            Future<String> connection = executor.submit(() -> {
                try (BootstrapPassword password = BootstrapPassword.copyAndClear("secret".toCharArray());
                     MinaSshOperation operation = passwordAuthentication
                             ? MinaSshOperation.openWithPassword(handler, endpoint, password, options(), () -> {
                                 SshClient created = SshClient.setUpDefaultClient();
                                 firstClient.compareAndSet(null, created);
                                 return created;
                             })
                             : MinaSshOperation.open(handler, endpoint, new SshCredentials(client), options(), () -> {
                                 SshClient created = SshClient.setUpDefaultClient();
                                 firstClient.compareAndSet(null, created);
                                 return created;
                             })) {
                    return operation.execute("printf resumed", new byte[0]).stdoutText();
                }
            });
            DecisionRequest request = awaitDecision(registry, connection);
            assertThat(connection).isNotDone();
            assertThat(firstClient.get().isClosed()).isTrue();
            assertThat(server.commands()).isEmpty();
            registry.decide(request.id(), new DecisionAnswer(0, ADMIN)).valueOrFailure("approve");
            assertThat(connection.get(10, TimeUnit.SECONDS)).isEqualTo("resumed");
            assertThat(endpoint.expectedHostKey()).isNotEqualTo(host.getPublic());

            Future<MinaSshOperation> next = executor.submit(() ->
                    MinaSshOperation.open(handler, endpoint, new SshCredentials(client), options()));
            DecisionRequest nextRequest = awaitDecision(registry, next);
            assertThat(nextRequest.id()).isNotEqualTo(request.id());
            registry.decide(nextRequest.id(), new DecisionAnswer(1, ADMIN)).valueOrFailure("reject");
            assertThatThrownBy(() -> next.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ProvisioningException.class);
            assertThat(server.commands()).containsExactly("printf resumed");
        }
    }

    @Test
    void closingRegistryStopsWaitingConnection(@TempDir Path root) throws Exception {
        KeyPair client = keyPair();
        try (TestSshServer server = TestSshServer.start(root, keyPair(), client);
             java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
             DecisionRegistry registry = new DecisionRegistry(1, Runnable::run, (actor, scope) -> true)) {
            SshEndpoint wrong = new SshEndpoint("127.0.0.1", server.endpoint().port(), "orion", keyPair().getPublic());
            Future<MinaSshOperation> connection = executor.submit(() -> MinaSshOperation.open(
                    new ConnectionFailureHandler(registry), wrong, new SshCredentials(client), options()));
            awaitDecision(registry, connection);
            registry.close();
            assertThatThrownBy(() -> connection.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ProvisioningException.class);
            assertThat(server.commands()).isEmpty();
        }
    }

    private static DecisionRequest awaitDecision(DecisionRegistry registry, Future<?> connection) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registry.list(ADMIN).isEmpty() && System.nanoTime() < deadline) {
            if (connection.isDone()) connection.get();
            Thread.sleep(10);
        }
        assertThat(registry.list(ADMIN)).hasSize(1);
        return registry.list(ADMIN).getFirst();
    }

    private static ProvisioningOptions options() {
        return new ProvisioningOptions(Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(3), Duration.ofSeconds(10));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
