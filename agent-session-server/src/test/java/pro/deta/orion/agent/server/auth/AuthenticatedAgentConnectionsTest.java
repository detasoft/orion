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
        AtomicInteger publications = new AtomicInteger();
        AuthenticatedAgentConnections connections = new AuthenticatedAgentConnections(ignored ->
                publications.getAndIncrement() == 0
                        ? recordingSession(firstMessages, firstClosures)
                        : recordingSession(secondMessages, secondClosures));
        AtomicInteger firstRenewals = new AtomicInteger();
        TestConnection firstTransport = new TestConnection();
        AuthenticatedConnectionContext first = context("connection-1", firstTransport, firstRenewals);
        AgentControlHandler.Session firstSession = connections.activate(first);
        AuthenticatedConnectionContext second = context(
                "connection-2", new TestConnection(), new AtomicInteger());
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

    private static AuthenticatedConnectionContext context(
            String connectionId, TestConnection connection, AtomicInteger renewals) {
        return new AuthenticatedConnectionContext(
                AGENT_ID,
                GENERATION,
                LAUNCH_ID,
                INSTANCE_ID,
                "2.4.1",
                MACHINE,
                Map.of("pty", "true"),
                new ConnectionId(connectionId),
                connection,
                () -> {
                    renewals.incrementAndGet();
                    return AuthenticatedConnectionContext.RenewalResult.RENEWED;
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
}
