package pro.deta.orion.agent.server.auth;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.server.connection.AgentControlHandler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedAgentConnectionsTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final Duration HEARTBEAT_DEADLINE = Duration.ofSeconds(30);
    private static final AgentId AGENT_ID = new AgentId("agent-1");
    private static final AgentGeneration GENERATION = new AgentGeneration(3);
    private static final AgentLaunchId LAUNCH_ID =
            new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f"));
    private static final AgentInstanceId INSTANCE_ID =
            new AgentInstanceId(UUID.fromString("20010203-0405-0607-0809-0a0b0c0d0e0f"));
    private static final MachineInfo MACHINE = new MachineInfo("worker-1", "linux", "aarch64");

    @Test
    void initialConnectionOwnsMessagesUntilItCloses() {
        List<AgentMessage> messages = new ArrayList<>();
        List<Throwable> closures = new ArrayList<>();
        AuthenticatedAgentConnections connections = new AuthenticatedAgentConnections(
                ignored -> recordingSession(messages, closures));
        TestConnection transport = new TestConnection();
        AuthenticatedConnectionContext context = context("connection-1", transport, new AtomicInteger());
        AgentControlHandler.Session session = connections.activate(context);
        AgentMessage.Heartbeat heartbeat = heartbeat();

        session.onMessage(heartbeat);

        assertThat(connections.active(AGENT_ID)).contains(context);
        assertThat(messages).containsExactly(heartbeat);

        session.onClosed(null);

        assertThat(connections.active(AGENT_ID)).isEmpty();
        assertThat(closures).containsExactly((Throwable) null);
    }

    @Test
    void replacementRevokesOldConnectionAndIgnoresItsLateCallbacks() {
        List<AgentMessage> firstMessages = new ArrayList<>();
        List<Throwable> firstClosures = new ArrayList<>();
        List<AgentMessage> secondMessages = new ArrayList<>();
        List<Throwable> secondClosures = new ArrayList<>();
        List<Observation> firstObservations = new ArrayList<>();
        List<Observation> secondObservations = new ArrayList<>();
        AtomicInteger publications = new AtomicInteger();
        AuthenticatedAgentConnections connections = new AuthenticatedAgentConnections(ignored ->
                publications.getAndIncrement() == 0
                        ? recordingSession(firstMessages, firstClosures)
                        : recordingSession(secondMessages, secondClosures));
        AtomicInteger firstRenewals = new AtomicInteger();
        TestConnection firstTransport = new TestConnection();
        AuthenticatedConnectionContext first = context(
                GENERATION,
                LAUNCH_ID,
                "connection-1",
                firstTransport,
                firstRenewals,
                firstObservations);
        AgentControlHandler.Session firstSession = connections.activate(first);
        AuthenticatedConnectionContext second = context(
                GENERATION,
                LAUNCH_ID,
                "connection-2",
                new TestConnection(),
                new AtomicInteger(),
                secondObservations);
        AgentControlHandler.Session secondSession = connections.activate(second);
        AgentMessage.Heartbeat heartbeat = heartbeat();

        firstSession.onMessage(heartbeat);
        firstSession.onClosed(new IllegalStateException("late close"));
        secondSession.onMessage(heartbeat);

        assertThat(connections.active(AGENT_ID)).contains(second);
        assertThat(firstTransport.closed).isTrue();
        assertThat(firstClosures).containsExactly((Throwable) null);
        assertThat(firstMessages).isEmpty();
        assertThat(secondMessages).containsExactly(heartbeat);
        assertThat(secondClosures).isEmpty();
        assertThat(firstObservations).hasSize(1);
        assertThat(secondObservations).hasSize(2);
        assertThat(first.renewReconnectToken())
                .isEqualTo(AuthenticatedConnectionContext.RenewalResult.REJECTED);
        assertThat(firstRenewals).hasValue(0);
    }

    @Test
    void takeoverWaitsForCallbackWithoutBlockingItsActiveLookup() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowLookup = new CountDownLatch(1);
        AuthenticatedAgentConnections[] owner = new AuthenticatedAgentConnections[1];
        owner[0] = new AuthenticatedAgentConnections(ignored -> new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
                callbackEntered.countDown();
                try {
                    assertThat(allowLookup.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                assertThat(owner[0].active(AGENT_ID)).isPresent();
            }

            @Override
            public void onClosed(Throwable failure) {
            }
        });
        AuthenticatedConnectionContext first = context(
                "connection-1", new TestConnection(), new AtomicInteger());
        AgentControlHandler.Session firstSession = owner[0].activate(first);
        AuthenticatedConnectionContext second = context(
                "connection-2", new TestConnection(), new AtomicInteger());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var callback = executor.submit(() -> firstSession.onMessage(heartbeat()));
            assertThat(callbackEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var takeover = executor.submit(() -> owner[0].activate(second));
            assertThatThrownBy(() -> takeover.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowLookup.countDown();

            callback.get(10, TimeUnit.SECONDS);
            takeover.get(10, TimeUnit.SECONDS);
            assertThat(owner[0].active(AGENT_ID)).contains(second);
        }
    }

    @Test
    void generationRevocationClosesCurrentConnectionAndFencesLatePublication() {
        List<Throwable> closures = new ArrayList<>();
        AtomicInteger publications = new AtomicInteger();
        AuthenticatedAgentConnections connections = new AuthenticatedAgentConnections(
                ignored -> {
                    publications.incrementAndGet();
                    return recordingSession(new ArrayList<>(), closures);
                });
        AtomicInteger renewals = new AtomicInteger();
        TestConnection currentTransport = new TestConnection();
        AuthenticatedConnectionContext current = context(
                GENERATION, LAUNCH_ID, "connection-1", currentTransport, renewals);
        connections.activate(current);

        connections.revokeGeneration(AGENT_ID, GENERATION);

        assertThat(connections.active(AGENT_ID)).isEmpty();
        assertThat(currentTransport.closed).isTrue();
        assertThat(closures).containsExactly((Throwable) null);
        assertThat(current.renewReconnectToken())
                .isEqualTo(AuthenticatedConnectionContext.RenewalResult.REJECTED);
        assertThat(renewals).hasValue(0);

        TestConnection lateTransport = new TestConnection();
        AuthenticatedConnectionContext late = context(
                GENERATION, LAUNCH_ID, "connection-late", lateTransport, new AtomicInteger());
        assertThatThrownBy(() -> connections.activate(late))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lateTransport.closed).isTrue();
        assertThat(connections.active(AGENT_ID)).isEmpty();
        assertThat(publications).hasValue(1);
    }

    @Test
    void staleGenerationRevocationDoesNotCloseNewerConnection() {
        AuthenticatedAgentConnections connections = new AuthenticatedAgentConnections(
                ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()));
        AgentGeneration replacementGeneration = new AgentGeneration(GENERATION.value() + 1);
        AgentLaunchId replacementLaunch = new AgentLaunchId(UUID.randomUUID());
        TestConnection replacementTransport = new TestConnection();
        AuthenticatedConnectionContext replacement = context(
                replacementGeneration,
                replacementLaunch,
                "connection-2",
                replacementTransport,
                new AtomicInteger());
        connections.activate(replacement);

        connections.revokeGeneration(AGENT_ID, GENERATION);

        assertThat(connections.active(AGENT_ID)).contains(replacement);
        assertThat(replacementTransport.closed).isFalse();
    }

    @Test
    void heartbeatRestoresTimedOutLaunchAndUsesServerTimeForObservation() {
        TestClock clock = new TestClock(NOW);
        AtomicInteger renewals = new AtomicInteger();
        List<Observation> observations = new ArrayList<>();
        AuthenticatedAgentConnections connections = AuthenticatedAgentConnections.withPolicy(
                ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()),
                clock,
                HEARTBEAT_DEADLINE);
        AuthenticatedConnectionContext context = context(
                GENERATION,
                LAUNCH_ID,
                "connection-1",
                new TestConnection(),
                renewals,
                observations);
        AgentControlHandler.Session session = connections.activate(context);

        assertThat(connections.available(AGENT_ID, LAUNCH_ID)).isTrue();
        assertThat(observations).containsExactly(new Observation(
                "2.4.1", MACHINE, Map.of("pty", "true"), NOW));

        clock.advance(HEARTBEAT_DEADLINE);
        assertThat(connections.available(AGENT_ID, LAUNCH_ID)).isFalse();

        session.onMessage(new AgentMessage.Heartbeat(
                AGENT_ID, INSTANCE_ID, Long.MAX_VALUE));

        assertThat(connections.available(AGENT_ID, LAUNCH_ID)).isTrue();
        assertThat(renewals).hasValue(1);
        assertThat(observations.getLast().observedAt()).isEqualTo(clock.instant());
    }

    @Test
    void agentStatusRecordsValidatedMetadataAndReachesDownstream() {
        TestClock clock = new TestClock(NOW);
        List<Observation> observations = new ArrayList<>();
        List<AgentMessage> messages = new ArrayList<>();
        AuthenticatedAgentConnections connections = AuthenticatedAgentConnections.withPolicy(
                ignored -> recordingSession(messages, new ArrayList<>()),
                clock,
                HEARTBEAT_DEADLINE);
        AgentControlHandler.Session session = connections.activate(context(
                GENERATION,
                LAUNCH_ID,
                "connection-1",
                new TestConnection(),
                new AtomicInteger(),
                observations));
        observations.clear();
        MachineInfo updatedMachine = new MachineInfo("worker-1", "linux", "x86_64");
        AgentMessage.AgentStatus status = new AgentMessage.AgentStatus(
                AGENT_ID,
                INSTANCE_ID,
                "2.5.0",
                updatedMachine,
                2,
                Map.of("load", "0.2"),
                Map.of("pty", "true", "gpu", "false"));

        session.onMessage(status);
        clock.advance(Duration.ofSeconds(1));
        session.onMessage(heartbeat());

        assertThat(messages).containsExactly(status, heartbeat());
        assertThat(observations).containsExactly(
                new Observation("2.5.0", updatedMachine, status.capabilities(), NOW),
                new Observation("2.5.0", updatedMachine, status.capabilities(), clock.instant()));
    }

    @Test
    void mismatchedHeartbeatAndStatusIdentitiesCloseTheirConnections() {
        TestClock clock = new TestClock(NOW);
        List<AgentMessage> messages = new ArrayList<>();
        List<Throwable> closures = new ArrayList<>();
        AuthenticatedAgentConnections connections = AuthenticatedAgentConnections.withPolicy(
                ignored -> recordingSession(messages, closures), clock, HEARTBEAT_DEADLINE);
        TestConnection heartbeatTransport = new TestConnection();
        AgentControlHandler.Session heartbeatSession = connections.activate(context(
                GENERATION, LAUNCH_ID, "connection-1", heartbeatTransport, new AtomicInteger()));

        heartbeatSession.onMessage(new AgentMessage.Heartbeat(
                new AgentId("other-agent"), INSTANCE_ID, 1L));

        assertThat(heartbeatTransport.closed).isTrue();
        assertThat(connections.active(AGENT_ID)).isEmpty();

        TestConnection statusTransport = new TestConnection();
        AgentControlHandler.Session statusSession = connections.activate(context(
                GENERATION, LAUNCH_ID, "connection-2", statusTransport, new AtomicInteger()));
        statusSession.onMessage(new AgentMessage.AgentStatus(
                AGENT_ID,
                new AgentInstanceId(UUID.randomUUID()),
                "2.4.1",
                MACHINE,
                0,
                Map.of(),
                Map.of()));

        assertThat(statusTransport.closed).isTrue();
        assertThat(connections.active(AGENT_ID)).isEmpty();
        assertThat(messages).isEmpty();
        assertThat(closures).hasSize(2).allMatch(IllegalArgumentException.class::isInstance);
    }

    @Test
    void shutdownReleasesAnOnlineWaitAndRejectsLaterActivation() throws Exception {
        AuthenticatedAgentConnections connections = new AuthenticatedAgentConnections(
                ignored -> recordingSession(new ArrayList<>(), new ArrayList<>()));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> connections.awaitOnline(
                    AGENT_ID, LAUNCH_ID, Duration.ofSeconds(10)));

            connections.close();

            assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
            TestConnection transport = new TestConnection();
            assertThatThrownBy(() -> connections.activate(context(
                    "connection-after-close", transport, new AtomicInteger())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(transport.closed).isTrue();
        }
    }

    private static AuthenticatedConnectionContext context(
            String connectionId, TestConnection connection, AtomicInteger renewals) {
        return context(GENERATION, LAUNCH_ID, connectionId, connection, renewals);
    }

    private static AuthenticatedConnectionContext context(
            AgentGeneration generation,
            AgentLaunchId launchId,
            String connectionId,
            TestConnection connection,
            AtomicInteger renewals) {
        return context(generation, launchId, connectionId, connection, renewals, new ArrayList<>());
    }

    private static AuthenticatedConnectionContext context(
            AgentGeneration generation,
            AgentLaunchId launchId,
            String connectionId,
            TestConnection connection,
            AtomicInteger renewals,
            List<Observation> observations) {
        return new AuthenticatedConnectionContext(
                AGENT_ID,
                generation,
                launchId,
                INSTANCE_ID,
                "2.4.1",
                MACHINE,
                Map.of("pty", "true"),
                new ConnectionId(connectionId),
                connection,
                () -> {
                    renewals.incrementAndGet();
                    return AuthenticatedConnectionContext.RenewalResult.RENEWED;
                },
                (agentVersion, machine, capabilities, observedAt) -> {
                    observations.add(new Observation(agentVersion, machine, capabilities, observedAt));
                    return AuthenticatedConnectionContext.ObservationResult.RECORDED;
                });
    }

    private static AgentMessage.Heartbeat heartbeat() {
        return new AgentMessage.Heartbeat(AGENT_ID, INSTANCE_ID, 1L);
    }

    private static AgentControlHandler.Session recordingSession(
            List<AgentMessage> messages, List<Throwable> closures) {
        return new AgentControlHandler.Session() {
            @Override
            public void onMessage(AgentMessage message) {
                messages.add(message);
            }

            @Override
            public void onClosed(Throwable failure) {
                closures.add(failure);
            }
        };
    }

    private static final class TestConnection implements AgentControlHandler.Connection {
        private boolean closed;

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void handshakeComplete() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private record Observation(
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            Instant observedAt) {
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
