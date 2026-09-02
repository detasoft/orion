package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.SessionStreamRequest;
import pro.deta.orion.agentd.transport.TransportSignal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentControlServiceTest {
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());

    @Test
    void registersCallbacksBeforeConnectingAndNegotiatesWelcome() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        AgentLaunchContext context = AgentHandshakeTest.context();
        AgentControlService service = service(transport, context, Duration.ofSeconds(1));

        service.start();

        AgentMessage.Hello hello = (AgentMessage.Hello) CODEC.decode(transport.controls.getFirst());
        assertThat(transport.callbacksPresentAtConnect).isTrue();
        assertThat(hello.authentication()).isPresent();
        assertThat(service.connection()).get().extracting(AgentConnection::connectionId)
                .isEqualTo(new ConnectionId("connection-1"));
        ReconnectToken reconnectToken = service.connection().orElseThrow().reconnectToken();
        service.close();
        assertThat(transport.closed).isTrue();
        assertThat(context.permit().copyBytes()).containsOnly(0);
        assertThat(reconnectToken.copyBytes()).containsOnly(0);
    }

    @Test
    void rejectsMalformedUnexpectedAndUnauthenticatedFirstMessages() throws Exception {
        assertStartupFails(new byte[]{(byte) 0xff});
        assertStartupFails(CODEC.encode(new AgentMessage.RequestSessionList()));
        assertStartupFails(CODEC.encode(new AgentMessage.Welcome(
                AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                new ConnectionId("connection-1"), Map.of())));
    }

    @Test
    void transportFailureAndHandshakeTimeoutFailWithRedactedErrors() {
        FakeTransport failed = new FakeTransport();
        failed.connectFailure = new IllegalStateException("permit-secret");
        AgentControlService failureService = service(
                failed, AgentHandshakeTest.context(), Duration.ofSeconds(1));

        assertThatExceptionOfType(HandshakeException.class)
                .isThrownBy(failureService::start)
                .withMessageNotContaining("permit-secret");

        AgentControlService timeoutService = service(
                new FakeTransport(), AgentHandshakeTest.context(), Duration.ZERO);
        assertThatExceptionOfType(HandshakeException.class)
                .isThrownBy(timeoutService::start)
                .withMessageContaining("timed out");
    }

    @Test
    void appliesOneDeadlineAcrossConnectSendAndWelcome() {
        AtomicLong nanoTime = new AtomicLong();
        FakeTransport transport = new FakeTransport();
        transport.reply = AgentHandshakeTest.welcome("connection-1", (byte) 9);
        transport.nanoTime = nanoTime;
        transport.connectElapsedNanos = Duration.ofMillis(600).toNanos();
        transport.sendElapsedNanos = Duration.ofMillis(600).toNanos();
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofSeconds(1), nanoTime::get);

        assertThatExceptionOfType(HandshakeException.class)
                .isThrownBy(service::start)
                .withMessageContaining("timed out");
        assertThat(transport.closed).isTrue();
    }

    private static void assertStartupFails(byte[] reply) {
        FakeTransport transport = new FakeTransport();
        transport.rawReply = reply;
        AgentControlService service = service(
                transport, AgentHandshakeTest.context(), Duration.ofSeconds(1));
        assertThatExceptionOfType(HandshakeException.class).isThrownBy(service::start);
    }

    private static AgentControlService service(
            FakeTransport transport, AgentLaunchContext context, Duration timeout) {
        return service(transport, context, timeout, System::nanoTime);
    }

    private static AgentControlService service(
            FakeTransport transport,
            AgentLaunchContext context,
            Duration timeout,
            java.util.function.LongSupplier nanoTime
    ) {
        return new AgentControlService(
                transport, CODEC, new AgentHandshake(), context, "2.4.1",
                new MachineInfo("runner-1", "Linux", "aarch64"), Map.of("pty", "true"), timeout, nanoTime);
    }

    private static final class FakeTransport implements AgentTransport {
        private final List<byte[]> controls = new ArrayList<>();
        private Consumer<byte[]> controlReceiver;
        private Consumer<TransportSignal> signalReceiver;
        private AgentMessage.Welcome reply;
        private byte[] rawReply;
        private RuntimeException connectFailure;
        private AtomicLong nanoTime;
        private long connectElapsedNanos;
        private long sendElapsedNanos;
        private boolean callbacksPresentAtConnect;
        private boolean closed;

        @Override
        public CompletionStage<Void> connect() {
            callbacksPresentAtConnect = controlReceiver != null && signalReceiver != null;
            advance(connectElapsedNanos);
            return connectFailure == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(connectFailure);
        }

        @Override
        public CompletionStage<Void> sendControlCbor(byte[] item) {
            controls.add(item.clone());
            advance(sendElapsedNanos);
            try {
                if (reply != null) {
                    controlReceiver.accept(CODEC.encode(reply));
                } else if (rawReply != null) {
                    controlReceiver.accept(rawReply.clone());
                }
                return CompletableFuture.completedFuture(null);
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public CompletionStage<Void> sendSessionCbor(SessionId id, byte[] item) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Void> openSession(SessionId id, SessionStreamRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public void onControlCbor(Consumer<byte[]> receiver) {
            controlReceiver = receiver;
        }

        @Override
        public void onSessionCbor(BiConsumer<SessionId, byte[]> receiver) {
        }

        @Override
        public void onSignal(Consumer<TransportSignal> receiver) {
            signalReceiver = receiver;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void advance(long elapsedNanos) {
            if (nanoTime != null) {
                nanoTime.addAndGet(elapsedNanos);
            }
        }
    }
}
