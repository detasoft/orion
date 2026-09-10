package pro.deta.orion.agent.server.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentAuthentication;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolVersion;
import pro.deta.orion.agent.protocol.JournalFormatVersion;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.AgentRecord;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentControlAuthenticatorTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration PERMIT_LIFETIME = Duration.ofMinutes(2);
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final MachineInfo MACHINE = new MachineInfo("worker-1", "linux", "aarch64");

    @TempDir
    Path root;

    @Test
    void initialLoginCommitsReconnectCredentialBeforeWelcomeAndContextPublication() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();

            authenticator.open(connection).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));

            AgentRecord persisted = registry.find(TestIdentity.AGENT_ID).orElseThrow();
            assertThat(persisted.launch().orElseThrow().launchPermit()).isEmpty();
            assertThat(persisted.launch().orElseThrow().reconnectToken()).isPresent();
            assertThat(connection.sent).singleElement().isInstanceOf(AgentMessage.Welcome.class);
            assertThat(authenticated).isEmpty();

            connection.sendCompletion.complete(null);

            assertThat(connection.handshakeComplete).isTrue();
            assertThat(authenticated).singleElement().satisfies(context -> {
                assertThat(context.agentId()).isEqualTo(TestIdentity.AGENT_ID);
                assertThat(context.generation()).isEqualTo(launch.generation());
                assertThat(context.launchId()).isEqualTo(launch.launchId());
                assertThat(context.instanceId()).isEqualTo(TestIdentity.INSTANCE_ID);
                assertThat(context.agentVersion()).isEqualTo("2.4.1");
                assertThat(context.machine()).isEqualTo(MACHINE);
                assertThat(context.capabilities()).containsExactlyEntriesOf(Map.of("pty", "true"));
                assertThat(context.connection()).isSameAs(connection);
                assertThat(context.connectionId()).isNotNull();
            });
            issued.permit().close();
        }
    }

    @Test
    void permitIssuancePersistsOnlyDigestWithBoundedExpiry() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, new ArrayList<>());

            AgentControlAuthenticator.PermitIssueResult result = authenticator.issueLaunchPermit(
                    TestIdentity.AGENT_ID, launch.generation(), launch.launchId());

            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) result;
            byte[] encoded = issued.permit().copyBytes();
            assertThat(encoded).hasSize(43);
            assertThat(Base64.getUrlDecoder().decode(encoded)).hasSize(32);
            assertThat(issued.permit().toString()).isEqualTo("ProvisioningLaunchPermit[redacted]");
            AgentRecord.Credential persisted = registry.find(TestIdentity.AGENT_ID).orElseThrow()
                    .launch().orElseThrow().launchPermit().orElseThrow();
            assertThat(persisted.expiresAt()).isEqualTo(NOW.plus(PERMIT_LIFETIME));
            assertThat(persisted.digest().toString()).isEqualTo("CredentialDigest[algorithm=SHA-256]");
            assertThat(authenticator.issueLaunchPermit(
                    TestIdentity.AGENT_ID, launch.generation(), launch.launchId()))
                    .isEqualTo(new AgentControlAuthenticator.PermitIssueResult.Failed(
                            AgentControlAuthenticator.Failure.REJECTED));
            issued.permit().close();
        }
    }

    @Test
    void reconnectTokenAuthenticatesAfterRegistryRestart() throws Exception {
        AgentRecord.Launch launch;
        byte[] reconnectToken;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, new ArrayList<>());
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection initial = new TestConnection();
            authenticator.open(initial).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            reconnectToken = welcomeToken(initial);
            initial.sendCompletion.complete(null);
            issued.permit().close();
        }

        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry reopened = new FileSystemAgentRegistry(root)) {
            AgentControlAuthenticator authenticator = authenticator(reopened, authenticated);
            TestConnection reconnect = new TestConnection();

            authenticator.open(reconnect).onMessage(hello(
                    launch, AgentAuthentication.Kind.RECONNECT_TOKEN, reconnectToken));
            reconnect.sendCompletion.complete(null);

            assertThat(welcomeToken(reconnect)).containsExactly(reconnectToken);
            assertThat(authenticated).hasSize(1);
            assertThat(reconnect.handshakeComplete).isTrue();
        }
    }

    @Test
    void authenticatedContextRenewsReconnectTokenFromCurrentServerTime() throws Exception {
        TestClock clock = new TestClock(NOW);
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated, clock);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();
            authenticator.open(connection).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            connection.sendCompletion.complete(null);
            clock.advance(Duration.ofMinutes(10));

            assertThat(authenticated.getFirst().renewReconnectToken())
                    .isEqualTo(AuthenticatedConnectionContext.RenewalResult.RENEWED);
            assertThat(registry.find(TestIdentity.AGENT_ID).orElseThrow().launch().orElseThrow()
                    .reconnectToken().orElseThrow().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(40)));
            issued.permit().close();
        }
    }

    @Test
    void missingAuthenticationIsRejectedWithoutWelcome() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            TestConnection connection = new TestConnection();

            authenticator.open(connection).onMessage(new AgentMessage.Hello(
                    AgentProtocolVersion.CURRENT,
                    JournalFormatVersion.CURRENT,
                    TestIdentity.AGENT_ID,
                    TestIdentity.INSTANCE_ID,
                    "2.4.1",
                    MACHINE,
                    Map.of()));

            assertRejected(connection, authenticated);
        }
    }

    @Test
    void unsupportedProtocolOrJournalVersionCannotConsumePermit() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            List<AgentMessage.Hello> invalid = List.of(
                    hello(launch, new AgentProtocolVersion(2), JournalFormatVersion.CURRENT, permit),
                    hello(launch, AgentProtocolVersion.CURRENT, new JournalFormatVersion(2), permit));

            for (AgentMessage.Hello hello : invalid) {
                TestConnection connection = new TestConnection();
                authenticator.open(connection).onMessage(hello);
                assertRejected(connection, authenticated);
            }
            assertThat(registry.find(TestIdentity.AGENT_ID).orElseThrow().launch().orElseThrow()
                    .launchPermit()).isPresent();
            issued.permit().close();
        }
    }

    @Test
    void expiredPermitIsRejectedWithoutConsumption() throws Exception {
        TestClock clock = new TestClock(NOW);
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated, clock);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            clock.advance(PERMIT_LIFETIME);
            TestConnection connection = new TestConnection();

            authenticator.open(connection).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));

            assertRejected(connection, authenticated);
            assertThat(registry.find(TestIdentity.AGENT_ID).orElseThrow().launch().orElseThrow()
                    .launchPermit()).isPresent();
            issued.permit().close();
        }
    }

    @Test
    void expiredReconnectTokenIsRejected() throws Exception {
        TestClock clock = new TestClock(NOW);
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated, clock);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection initial = new TestConnection();
            authenticator.open(initial).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            byte[] reconnectToken = welcomeToken(initial);
            initial.sendCompletion.complete(null);
            authenticated.clear();
            clock.advance(TOKEN_LIFETIME);
            TestConnection reconnect = new TestConnection();

            authenticator.open(reconnect).onMessage(hello(
                    launch, AgentAuthentication.Kind.RECONNECT_TOKEN, reconnectToken));

            assertRejected(reconnect, authenticated);
            issued.permit().close();
        }
    }

    @Test
    void identityMismatchCannotConsumePermit() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();

            authenticator.open(connection).onMessage(hello(
                    new pro.deta.orion.agent.protocol.AgentId("agent-2"), launch,
                    AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT, permit));

            assertRejected(connection, authenticated);
            assertThat(registry.find(TestIdentity.AGENT_ID).orElseThrow().launch().orElseThrow()
                    .launchPermit()).isPresent();
            issued.permit().close();
        }
    }

    @Test
    void wrongCredentialAndSupersededLaunchAreRejected() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            TestConnection wrongCredential = new TestConnection();
            authenticator.open(wrongCredential).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, new byte[32]));
            assertRejected(wrongCredential, authenticated);

            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            AgentRecord.Launch replacement = registry.allocateLaunch(TestIdentity.AGENT_ID)
                    .launch().orElseThrow();
            TestConnection superseded = new TestConnection();
            authenticator.open(superseded).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));

            assertRejected(superseded, authenticated);
            assertThat(registry.find(TestIdentity.AGENT_ID).orElseThrow().launch()).contains(replacement);
            issued.permit().close();
        }
    }

    @Test
    void concurrentPermitUseAuthenticatesExactlyOneConnection() throws Exception {
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, new ArrayList<>());
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            CountDownLatch start = new CountDownLatch(1);
            List<TestConnection> connections = List.of(new TestConnection(), new TestConnection());
            List<Future<?>> attempts = new ArrayList<>();
            for (TestConnection connection : connections) {
                attempts.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    authenticator.open(connection).onMessage(hello(
                            launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(10, TimeUnit.SECONDS);
            }

            assertThat(connections).filteredOn(connection -> connection.sent.size() == 1).hasSize(1);
            assertThat(connections).filteredOn(connection -> connection.closed).hasSize(1);
            assertThat(registry.find(TestIdentity.AGENT_ID).orElseThrow().launch().orElseThrow()
                    .reconnectToken()).isPresent();
            issued.permit().close();
        }
    }

    @Test
    void failedInitialWelcomeLeavesPermitConsumedAndContextUnpublished() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();
            authenticator.open(connection).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));

            connection.sendCompletion.completeExceptionally(new IllegalStateException("lost response"));

            assertThat(connection.closed).isTrue();
            assertThat(connection.handshakeComplete).isFalse();
            assertThat(authenticated).isEmpty();
            AgentRecord.Launch persisted = registry.find(TestIdentity.AGENT_ID)
                    .orElseThrow().launch().orElseThrow();
            assertThat(persisted.launchPermit()).isEmpty();
            assertThat(persisted.reconnectToken()).isPresent();
            TestConnection retry = new TestConnection();
            authenticator.open(retry).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            assertRejected(retry, authenticated);
            issued.permit().close();
        }
    }

    @Test
    void failedReconnectWelcomeLeavesExistingTokenReusable() throws Exception {
        AgentRecord.Launch launch;
        byte[] reconnectToken;
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, new ArrayList<>());
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection initial = new TestConnection();
            authenticator.open(initial).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            reconnectToken = welcomeToken(initial);
            initial.sendCompletion.complete(null);
            issued.permit().close();

            TestConnection lost = new TestConnection();
            authenticator.open(lost).onMessage(hello(
                    launch, AgentAuthentication.Kind.RECONNECT_TOKEN, reconnectToken));
            lost.sendCompletion.completeExceptionally(new IllegalStateException("lost response"));
            assertThat(lost.closed).isTrue();

            List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
            AgentControlAuthenticator retryAuthenticator = authenticator(registry, authenticated);
            TestConnection retry = new TestConnection();
            retryAuthenticator.open(retry).onMessage(hello(
                    launch, AgentAuthentication.Kind.RECONNECT_TOKEN, reconnectToken));
            retry.sendCompletion.complete(null);

            assertThat(authenticated).hasSize(1);
            assertThat(welcomeToken(retry)).containsExactly(reconnectToken);
        }
    }

    @Test
    void messagesAndClosureAfterWelcomeReachPublishedSession() throws Exception {
        List<AgentMessage> messages = new ArrayList<>();
        List<Throwable> closures = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = AgentControlAuthenticator.withPolicy(
                    registry,
                    context -> new AgentControlHandler.Session() {
                        @Override
                        public void onMessage(AgentMessage message) {
                            messages.add(message);
                        }

                        @Override
                        public void onClosed(Throwable failure) {
                            closures.add(failure);
                        }
                    },
                    CLOCK,
                    new SecureRandom(),
                    PERMIT_LIFETIME,
                    TOKEN_LIFETIME);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();
            AgentControlHandler.Session session = authenticator.open(connection);
            session.onMessage(hello(launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            connection.sendCompletion.complete(null);
            AgentMessage.Heartbeat heartbeat = new AgentMessage.Heartbeat(
                    TestIdentity.AGENT_ID, TestIdentity.INSTANCE_ID, NOW.toEpochMilli());

            session.onMessage(heartbeat);
            session.onClosed(null);
            session.onClosed(new IllegalStateException("late close"));
            session.onMessage(heartbeat);

            assertThat(messages).containsExactly(heartbeat);
            assertThat(closures).hasSize(1);
            assertThat(closures.getFirst()).isNull();
            issued.permit().close();
        }
    }

    @Test
    void delegateCallbackDoesNotHoldAuthenticationMonitor() throws Exception {
        CountDownLatch messageEntered = new CountDownLatch(1);
        CountDownLatch releaseMessage = new CountDownLatch(1);
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = AgentControlAuthenticator.withPolicy(
                    registry,
                    context -> new AgentControlHandler.Session() {
                        @Override
                        public void onMessage(AgentMessage message) {
                            messageEntered.countDown();
                            try {
                                assertThat(releaseMessage.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(failure);
                            }
                        }

                        @Override
                        public void onClosed(Throwable failure) {
                        }
                    },
                    CLOCK,
                    new SecureRandom(),
                    PERMIT_LIFETIME,
                    TOKEN_LIFETIME);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();
            AgentControlHandler.Session session = authenticator.open(connection);
            session.onMessage(hello(launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            connection.sendCompletion.complete(null);
            Future<?> message = executor.submit(() -> session.onMessage(new AgentMessage.Heartbeat(
                    TestIdentity.AGENT_ID, TestIdentity.INSTANCE_ID, NOW.toEpochMilli())));
            assertThat(messageEntered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> closed = executor.submit(() -> session.onClosed(null));
            closed.get(2, TimeUnit.SECONDS);
            releaseMessage.countDown();
            message.get(10, TimeUnit.SECONDS);
            issued.permit().close();
        }
    }

    @Test
    void persistenceFailureCannotSendWelcomeOrPublishContext() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root);
        AgentRecord.Launch launch = prepareLaunch(registry);
        AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
        AgentControlAuthenticator.PermitIssueResult.Issued issued =
                (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                        TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
        byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
        registry.close();
        TestConnection connection = new TestConnection();

        authenticator.open(connection).onMessage(hello(
                launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));

        assertRejected(connection, authenticated);
        issued.permit().close();
    }

    @Test
    void extraMessageBeforeWelcomeDeliveryIsRejected() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();
            AgentControlHandler.Session session = authenticator.open(connection);
            session.onMessage(hello(launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));

            session.onMessage(new AgentMessage.Heartbeat(
                    TestIdentity.AGENT_ID, TestIdentity.INSTANCE_ID, NOW.toEpochMilli()));
            connection.sendCompletion.complete(null);

            assertThat(connection.closed).isTrue();
            assertThat(connection.handshakeComplete).isFalse();
            assertThat(authenticated).isEmpty();
            issued.permit().close();
        }
    }

    @Test
    void renewalCannotReviveSupersededGeneration() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            AgentControlAuthenticator.PermitIssueResult.Issued issued =
                    (AgentControlAuthenticator.PermitIssueResult.Issued) authenticator.issueLaunchPermit(
                            TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            byte[] permit = Base64.getUrlDecoder().decode(issued.permit().copyBytes());
            TestConnection connection = new TestConnection();
            authenticator.open(connection).onMessage(hello(
                    launch, AgentAuthentication.Kind.LAUNCH_PERMIT, permit));
            connection.sendCompletion.complete(null);
            AgentRecord replacement = registry.allocateLaunch(TestIdentity.AGENT_ID);

            assertThat(authenticated.getFirst().renewReconnectToken())
                    .isEqualTo(AuthenticatedConnectionContext.RenewalResult.REJECTED);
            assertThat(registry.find(TestIdentity.AGENT_ID)).contains(replacement);
            issued.permit().close();
        }
    }

    @Test
    void closedSessionCannotRenewItsReconnectToken() throws Exception {
        TestClock clock = new TestClock(NOW);
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated, clock);
            var issued = (AgentControlAuthenticator.PermitIssueResult.Issued)
                    authenticator.issueLaunchPermit(TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            try (var permit = issued.permit()) {
                TestConnection connection = new TestConnection();
                AgentControlHandler.Session session = authenticator.open(connection);
                session.onMessage(hello(launch, AgentAuthentication.Kind.LAUNCH_PERMIT,
                        Base64.getUrlDecoder().decode(permit.copyBytes())));
                connection.sendCompletion.complete(null);
                session.onClosed(null);
                AgentRecord before = registry.find(TestIdentity.AGENT_ID).orElseThrow();
                clock.advance(Duration.ofMinutes(1));

                assertThat(authenticated.getFirst().renewReconnectToken())
                        .isEqualTo(AuthenticatedConnectionContext.RenewalResult.REJECTED);
                assertThat(registry.find(TestIdentity.AGENT_ID)).contains(before);
            }
        }
    }

    @Test
    void generationRevokedDuringWelcomeCannotPublishContext() throws Exception {
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated);
            var issued = (AgentControlAuthenticator.PermitIssueResult.Issued)
                    authenticator.issueLaunchPermit(TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            try (var permit = issued.permit()) {
                TestConnection connection = new TestConnection();
                authenticator.open(connection).onMessage(hello(launch, AgentAuthentication.Kind.LAUNCH_PERMIT,
                        Base64.getUrlDecoder().decode(permit.copyBytes())));
                AgentRecord replacement = registry.allocateLaunch(TestIdentity.AGENT_ID);

                connection.sendCompletion.complete(null);

                assertThat(authenticated).isEmpty();
                assertThat(connection.closed).isTrue();
                assertThat(connection.handshakeComplete).isFalse();
                assertThat(registry.find(TestIdentity.AGENT_ID)).contains(replacement);
            }
        }
    }

    @Test
    void tokenExpiredDuringWelcomeCannotPublishContext() throws Exception {
        TestClock clock = new TestClock(NOW);
        List<AuthenticatedConnectionContext> authenticated = new ArrayList<>();
        try (FileSystemAgentRegistry registry = new FileSystemAgentRegistry(root)) {
            AgentRecord.Launch launch = prepareLaunch(registry);
            AgentControlAuthenticator authenticator = authenticator(registry, authenticated, clock);
            var issued = (AgentControlAuthenticator.PermitIssueResult.Issued)
                    authenticator.issueLaunchPermit(TestIdentity.AGENT_ID, launch.generation(), launch.launchId());
            try (var permit = issued.permit()) {
                TestConnection connection = new TestConnection();
                authenticator.open(connection).onMessage(hello(launch, AgentAuthentication.Kind.LAUNCH_PERMIT,
                        Base64.getUrlDecoder().decode(permit.copyBytes())));
                clock.advance(TOKEN_LIFETIME);

                connection.sendCompletion.complete(null);

                assertThat(authenticated).isEmpty();
                assertThat(connection.closed).isTrue();
                assertThat(connection.handshakeComplete).isFalse();
            }
        }
    }

    private AgentControlAuthenticator authenticator(
            FileSystemAgentRegistry registry, List<AuthenticatedConnectionContext> authenticated) {
        return authenticator(registry, authenticated, CLOCK);
    }

    private AgentControlAuthenticator authenticator(
            FileSystemAgentRegistry registry,
            List<AuthenticatedConnectionContext> authenticated,
            Clock clock) {
        return AgentControlAuthenticator.withPolicy(
                registry,
                context -> {
                    authenticated.add(context);
                    return new AgentControlHandler.Session() {
                        @Override
                        public void onMessage(AgentMessage message) {
                        }

                        @Override
                        public void onClosed(Throwable failure) {
                        }
                    };
                },
                clock,
                new SecureRandom(),
                PERMIT_LIFETIME,
                TOKEN_LIFETIME);
    }

    private static AgentRecord.Launch prepareLaunch(FileSystemAgentRegistry registry) throws Exception {
        registry.register(TestIdentity.AGENT_ID, "Build agent");
        return registry.allocateLaunch(TestIdentity.AGENT_ID).launch().orElseThrow();
    }

    private static AgentMessage.Hello hello(
            AgentRecord.Launch launch, AgentAuthentication.Kind kind, byte[] credential) {
        return hello(TestIdentity.AGENT_ID, launch, AgentProtocolVersion.CURRENT,
                JournalFormatVersion.CURRENT, kind, credential);
    }

    private static AgentMessage.Hello hello(
            AgentRecord.Launch launch,
            AgentProtocolVersion protocolVersion,
            JournalFormatVersion journalVersion,
            byte[] credential) {
        return hello(TestIdentity.AGENT_ID, launch, protocolVersion, journalVersion,
                AgentAuthentication.Kind.LAUNCH_PERMIT, credential);
    }

    private static AgentMessage.Hello hello(
            pro.deta.orion.agent.protocol.AgentId agentId,
            AgentRecord.Launch launch,
            AgentProtocolVersion protocolVersion,
            JournalFormatVersion journalVersion,
            byte[] credential) {
        return hello(agentId, launch, protocolVersion, journalVersion,
                AgentAuthentication.Kind.LAUNCH_PERMIT, credential);
    }

    private static AgentMessage.Hello hello(
            pro.deta.orion.agent.protocol.AgentId agentId,
            AgentRecord.Launch launch,
            AgentProtocolVersion protocolVersion,
            JournalFormatVersion journalVersion,
            AgentAuthentication.Kind kind,
            byte[] credential) {
        return new AgentMessage.Hello(
                protocolVersion,
                journalVersion,
                agentId,
                TestIdentity.INSTANCE_ID,
                "2.4.1",
                MACHINE,
                Map.of("pty", "true"),
                Optional.of(new AgentAuthentication(
                        launch.generation(), launch.launchId(), kind, ProtocolBytes.copyOf(credential))));
    }

    private static byte[] welcomeToken(TestConnection connection) {
        AgentMessage.Welcome welcome = (AgentMessage.Welcome) connection.sent.getFirst();
        return welcome.reconnectToken().orElseThrow().toByteArray();
    }

    private static void assertRejected(
            TestConnection connection, List<AuthenticatedConnectionContext> authenticated) {
        assertThat(connection.closed).isTrue();
        assertThat(connection.sent).isEmpty();
        assertThat(connection.handshakeComplete).isFalse();
        assertThat(authenticated).isEmpty();
    }

    private static final class TestConnection implements AgentControlHandler.Connection {
        private final List<AgentMessage> sent = new ArrayList<>();
        private final CompletableFuture<Void> sendCompletion = new CompletableFuture<>();
        private boolean handshakeComplete;
        private boolean closed;

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            sent.add(message);
            return sendCompletion;
        }

        @Override
        public void handshakeComplete() {
            handshakeComplete = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TestIdentity {
        private static final pro.deta.orion.agent.protocol.AgentId AGENT_ID =
                new pro.deta.orion.agent.protocol.AgentId("agent-1");
        private static final pro.deta.orion.agent.protocol.AgentInstanceId INSTANCE_ID =
                new pro.deta.orion.agent.protocol.AgentInstanceId(
                        UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f"));
    }

    private static final class TestClock extends Clock {
        private Instant instant;

        private TestClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
