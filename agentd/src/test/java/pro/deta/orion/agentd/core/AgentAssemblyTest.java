package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.SessionStreamRequest;
import pro.deta.orion.agentd.transport.TransportSignal;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentAssemblyTest {
    @TempDir
    Path state;

    @Test
    void acquiresProcessLockBeforeConnectingTransport() throws Exception {
        AgentConfiguration configuration = configuration();
        AgentLaunchContext context = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
        RecordingTransport transport = new RecordingTransport();
        AgentProcessMetadata holderMetadata = new AgentProcessMetadata(
                44, 55, configuration.launchId(), configuration.generation());

        try (AgentProcessLock holder = new AgentProcessLock(configuration.processLockFile(), holderMetadata);
             Agent agent = Agent.create(configuration, context, transport,
                     new MachineInfo("runner", "linux", "aarch64"))) {
            holder.start();
            assertThatExceptionOfType(AgentStartupException.class).isThrownBy(agent::start);
            assertThat(transport.connectCalls).isZero();
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

    private AgentConfiguration configuration() {
        return new AgentConfiguration(
                URI.create("https://agent.test"), state,
                new AgentId("agent-1"), new AgentGeneration(1),
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                AgentProtocolLimits.defaults(), "1.0.0", state.resolve("runtime/session-host"));
    }

    private static final class RecordingTransport implements AgentTransport {
        private int connectCalls;

        @Override
        public CompletionStage<Void> connect() {
            connectCalls++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> sendControlCbor(byte[] item) {
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
        public void onControlMessage(Consumer<AgentMessage> receiver) {
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
    }
}
