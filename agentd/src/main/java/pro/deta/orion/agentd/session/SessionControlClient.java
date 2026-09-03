package pro.deta.orion.agentd.session;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class SessionControlClient {
    private final Duration operationTimeout;
    private final ControlTransportFactory transports;
    private final AtomicLong nextRequestId = new AtomicLong(1);

    public SessionControlClient(Duration operationTimeout) {
        this(operationTimeout, ControlTransportFactory.nativeTransports());
    }

    SessionControlClient(Duration operationTimeout, ControlTransportFactory transports) {
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout");
        this.transports = Objects.requireNonNull(transports, "transports");
        OperationDeadline.after(operationTimeout);
    }

    public ControlResult send(ControlEndpoint endpoint, ControlCommand command) {
        return send(endpoint, command, OperationDeadline.after(operationTimeout));
    }

    public ControlResult send(
            ControlEndpoint endpoint,
            ControlCommand command,
            OperationDeadline deadline
    ) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(deadline, "deadline");
        deadline = deadline.boundedBy(operationTimeout);
        ControlTransportFactory.Selection selection = transports.select(endpoint);
        if (selection instanceof ControlTransportFactory.Selection.Unsupported unsupported) {
            return new ControlResult.Failed(
                    command.commandId(), ControlResult.FailureKind.UNSUPPORTED_TRANSPORT, unsupported.detail());
        }
        long requestId = requestId();
        byte[] request;
        try {
            request = new NativeControlCodec().encode(command, requestId);
        } catch (IllegalArgumentException error) {
            return new ControlResult.Failed(
                    command.commandId(), ControlResult.FailureKind.VALIDATION, error.getMessage());
        }
        ControlTransport transport = ((ControlTransportFactory.Selection.Available) selection).transport();
        ControlTransport.Exchange first = transport.exchange(endpoint, request, deadline);
        ControlResult.FailureKind firstKind;
        String firstDetail;
        boolean firstMayHaveDelivered;
        if (first instanceof ControlTransport.Exchange.Response response) {
            ControlResult decoded = new NativeControlCodec().decode(command, requestId, response.frame());
            if (!isFramingFailure(decoded)) {
                return decoded;
            }
            if (command instanceof ControlCommand.Status) {
                return decoded;
            }
            ControlResult.Failed malformed = (ControlResult.Failed) decoded;
            if (!(command instanceof ControlCommand.Input)) {
                return failed(command, ControlResult.FailureKind.AMBIGUOUS_DELIVERY, malformed.detail());
            }
            firstKind = malformed.kind();
            firstDetail = malformed.detail();
            firstMayHaveDelivered = true;
        } else {
            ControlTransport.Exchange.Failed failure = (ControlTransport.Exchange.Failed) first;
            firstKind = failure.kind();
            firstDetail = failure.detail();
            firstMayHaveDelivered = failure.mayHaveDelivered();
        }
        if (command instanceof ControlCommand.Input && !deadline.expired()) {
            ControlTransport.Exchange retry = transport.exchange(endpoint, request, deadline);
            if (retry instanceof ControlTransport.Exchange.Response response) {
                ControlResult decoded = new NativeControlCodec().decode(command, requestId, response.frame());
                if (!isFramingFailure(decoded)) {
                    return decoded;
                }
                return failed(
                        command,
                        ControlResult.FailureKind.AMBIGUOUS_DELIVERY,
                        ((ControlResult.Failed) decoded).detail());
            }
            ControlTransport.Exchange.Failed retryFailure = (ControlTransport.Exchange.Failed) retry;
            if (firstMayHaveDelivered || retryFailure.mayHaveDelivered()) {
                return failed(command, ControlResult.FailureKind.AMBIGUOUS_DELIVERY, retryFailure.detail());
            }
            return failed(command, retryFailure.kind(), retryFailure.detail());
        }
        ControlResult.FailureKind kind = firstMayHaveDelivered
                ? ControlResult.FailureKind.AMBIGUOUS_DELIVERY
                : firstKind;
        return failed(command, kind, firstDetail);
    }

    private static boolean isFramingFailure(ControlResult result) {
        return result instanceof ControlResult.Failed failed
                && failed.kind() == ControlResult.FailureKind.FRAMING;
    }

    private ControlResult failed(ControlCommand command, ControlResult.FailureKind kind, String detail) {
        return new ControlResult.Failed(command.commandId(), kind, detail);
    }

    private long requestId() {
        long value = nextRequestId.getAndIncrement();
        if (value <= 0) {
            throw new IllegalStateException("native control request IDs are exhausted");
        }
        return value;
    }
}
