package pro.deta.orion.agentd.core;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.TransportSignal;

public final class AgentControlService implements AgentService {
    private static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    private final AgentTransport transport;
    private final AgentProtocolCodec codec;
    private final AgentHandshake handshake;
    private final AgentLaunchContext context;
    private final String agentVersion;
    private final MachineInfo machine;
    private final Map<String, String> capabilities;
    private final Duration timeout;
    private final LongSupplier nanoTime;
    private final java.util.concurrent.CompletableFuture<AgentConnection> negotiated =
            new java.util.concurrent.CompletableFuture<>();

    public AgentControlService(
            AgentTransport transport,
            AgentProtocolCodec codec,
            AgentHandshake handshake,
            AgentLaunchContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities
    ) {
        this(transport, codec, handshake, context, agentVersion, machine, capabilities,
                DEFAULT_HANDSHAKE_TIMEOUT, System::nanoTime);
    }

    AgentControlService(
            AgentTransport transport,
            AgentProtocolCodec codec,
            AgentHandshake handshake,
            AgentLaunchContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            Duration timeout
    ) {
        this(transport, codec, handshake, context, agentVersion, machine, capabilities,
                timeout, System::nanoTime);
    }

    AgentControlService(
            AgentTransport transport,
            AgentProtocolCodec codec,
            AgentHandshake handshake,
            AgentLaunchContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            Duration timeout,
            LongSupplier nanoTime
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.handshake = Objects.requireNonNull(handshake, "handshake");
        this.context = Objects.requireNonNull(context, "context");
        this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion");
        this.machine = Objects.requireNonNull(machine, "machine");
        this.capabilities = Map.copyOf(capabilities);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("handshake timeout must not be negative");
        }
    }

    @Override
    public void start() throws HandshakeException {
        transport.onControlCbor(this::receiveControl);
        transport.onSignal(this::receiveSignal);
        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        try {
            await(transport.connect(), deadline);
            AgentMessage.Hello hello = handshake.initialHello(context, agentVersion, machine, capabilities);
            await(transport.sendControlCbor(codec.encode(hello)), deadline);
            context.permit().close();
            await(negotiated, deadline);
        } catch (TimeoutException failure) {
            transport.close();
            throw new HandshakeException("AgentD control handshake timed out", failure);
        } catch (HandshakeException failure) {
            transport.close();
            throw failure;
        } catch (Exception failure) {
            transport.close();
            throw new HandshakeException("AgentD control handshake failed", failure);
        }
    }

    public Optional<AgentConnection> connection() {
        return handshake.connection();
    }

    @Override
    public void close() {
        negotiated.completeExceptionally(new HandshakeException("AgentD control service closed"));
        handshake.close();
        context.close();
        transport.close();
    }

    private void receiveControl(byte[] item) {
        if (negotiated.isDone()) {
            return;
        }
        try {
            AgentMessage message = codec.decode(item);
            if (!(message instanceof AgentMessage.Welcome welcome)) {
                throw new HandshakeException("First server control message is not WELCOME");
            }
            negotiated.complete(handshake.accept(welcome));
        } catch (Exception failure) {
            negotiated.completeExceptionally(failure instanceof HandshakeException
                    ? failure : new HandshakeException("Invalid server WELCOME"));
        }
    }

    private void receiveSignal(TransportSignal signal) {
        if (signal.kind() != TransportSignal.Kind.CONNECTED) {
            negotiated.completeExceptionally(new HandshakeException("AgentD transport disconnected"));
        }
    }

    private <T> T await(CompletionStage<T> operation, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException, HandshakeException {
        java.util.concurrent.CompletableFuture<T> future = operation.toCompletableFuture();
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) {
            future.cancel(true);
            throw new TimeoutException();
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw failure;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof HandshakeException handshakeFailure) {
                throw handshakeFailure;
            }
            throw failure;
        }
    }
}
