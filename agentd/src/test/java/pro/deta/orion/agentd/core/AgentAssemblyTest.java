package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.SessionStreamRequest;
import pro.deta.orion.agentd.transport.TransportSignal;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentAssemblyTest {
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());

    @TempDir
    Path state;

    @Test
    void acquiresProcessLockBeforeConnectingTransport() throws Exception {
        AgentConfiguration configuration = configuration();
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
        RecordingTransport transport = new RecordingTransport();
        AgentProcessMetadata holderMetadata = new AgentProcessMetadata(
                44, 55, configuration.launchId(), configuration.generation(), "/opt/orion/agentd");

        try (AgentProcessLock holder = new AgentProcessLock(configuration.processLockFile(), holderMetadata);
             Agent agent = Agent.create(configuration, context, transport,
                     new MachineInfo("runner", "linux", "aarch64"))) {
            holder.start();
            assertThatExceptionOfType(AgentStartupException.class).isThrownBy(agent::start);
            assertThat(transport.connectCalls).isZero();
            assertThat(configuration.sessionsDirectory()).doesNotExist();
        }
    }

    @Test
    void closeBeforeStartClearsLaunchPermit() {
        AgentConfiguration configuration = configuration();
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 7);
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(secret));
        Agent agent = Agent.create(
                configuration, context, new RecordingTransport(),
                new MachineInfo("runner", "linux", "aarch64"));

        assertThat(agent.configuration().sessionHostExecutable())
                .isEqualTo(state.resolve("runtime/session-host").toAbsolutePath());
        agent.close();

        assertThat(context.permit().copyBytes()).containsOnly(0);
    }

    @Test
    void assemblesDiscoveryAndReportsItsInitialSnapshot() throws Exception {
        AgentConfiguration configuration = configuration();
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
        RecordingTransport transport = new RecordingTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);

        try (Agent agent = Agent.create(configuration, context, transport,
                new MachineInfo("runner", "linux", "aarch64"))) {
            assertThat(configuration.sessionsDirectory()).doesNotExist();

            agent.start();
            transport.deliver(new AgentMessage.RequestSessionList());
            await(() -> transport.messages(AgentMessage.SessionList.class).size() == 1);

            assertThat(configuration.sessionsDirectory()).isDirectory();
            assertThat(transport.messages(AgentMessage.SessionList.class))
                    .singleElement()
                    .extracting(AgentMessage.SessionList::sessions)
                    .satisfies(sessions -> assertThat(sessions).isEmpty());
        }
    }

    private AgentConfiguration configuration() {
        return new AgentConfiguration(
                URI.create("https://agent.test"), state,
                new AgentId("agent-1"), new AgentGeneration(1),
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                AgentProtocolLimits.defaults(), "1.0.0", state.resolve("runtime/session-host"));
    }

    private static final class RecordingTransport implements AgentTransport {
        private final List<byte[]> controls = new CopyOnWriteArrayList<>();
        private int connectCalls;
        private AgentMessage reply;
        private Consumer<SequenceDecodeResult.Outcome<AgentMessageRecord>> controlReceiver;

        @Override
        public CompletionStage<Void> connect() {
            connectCalls++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> sendControlCbor(byte[] item) {
            controls.add(item.clone());
            try {
                if (CODEC.decode(item) instanceof AgentMessage.Hello && reply != null) {
                    controlReceiver.accept(decoded(reply));
                }
            } catch (AgentProtocolException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> sendSessionCbor(SessionId id, byte[] item) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> openSession(SessionId id, SessionStreamRequest request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeSession(SessionId sessionId) {
        }

        @Override
        public void onControlOutcome(Consumer<SequenceDecodeResult.Outcome<AgentMessageRecord>> receiver) {
            controlReceiver = receiver;
        }

        @Override
        public void onSessionMessage(BiConsumer<SessionId, AgentMessage> receiver) {
        }

        @Override
        public void onSignal(Consumer<TransportSignal> receiver) {
        }

        @Override
        public void close() {
        }

        private void deliver(AgentMessage message) {
            controlReceiver.accept(decoded(message));
        }

        private <T extends AgentMessage> List<T> messages(Class<T> type) throws AgentProtocolException {
            List<T> messages = new ArrayList<>();
            for (byte[] item : controls) {
                AgentMessage message = CODEC.decode(item);
                if (type.isInstance(message)) {
                    messages.add(type.cast(message));
                }
            }
            return messages;
        }
    }

    private static SequenceDecodeResult.Decoded<AgentMessageRecord> decoded(AgentMessage message) {
        try {
            return new SequenceDecodeResult.Decoded<>(
                    new AgentMessageRecord(message, ProtocolBytes.copyOf(CODEC.encode(message))));
        } catch (AgentProtocolException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(condition.evaluate()).isTrue();
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
