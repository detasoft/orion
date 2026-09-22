package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

public final class SessionControlClient {
    private final Duration operationTimeout;
    private final ControlTransportFactory transports;

    public SessionControlClient(Duration operationTimeout) {
        this(operationTimeout, ControlTransportFactory.nativeTransports());
    }

    SessionControlClient(Duration operationTimeout, ControlTransportFactory transports) {
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout");
        this.transports = Objects.requireNonNull(transports, "transports");
        OperationDeadline.after(operationTimeout);
    }

    public Connection open(ControlEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        UnixDomainControlTransport.Connection connection =
                endpoint.transport() == ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET
                        ? new UnixDomainControlTransport.Connection(endpoint) : null;
        return new Connection() {
            @Override
            public ControlResult send(ControlCommand command) {
                Objects.requireNonNull(command, "command");
                if (connection == null) {
                    return failed(command, ControlResult.FailureKind.UNSUPPORTED_TRANSPORT,
                            "named-pipe control awaits the native Windows session host");
                }
                return exchange(endpoint, command, OperationDeadline.after(operationTimeout),
                        (ignored, request, deadline) -> connection.exchange(request, deadline));
            }

            @Override
            public void close() throws IOException {
                if (connection != null) {
                    connection.close();
                }
            }
        };
    }

    /**
     * A single-owner connection: commands share one socket, each with its own operation deadline.
     * Connection failures are terminal; ambiguous commands are never retried.
     */
    public interface Connection extends AutoCloseable {
        ControlResult send(ControlCommand command);

        @Override
        void close() throws IOException;
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
            return failed(command, ControlResult.FailureKind.UNSUPPORTED_TRANSPORT, unsupported.detail());
        }
        return exchange(endpoint, command, deadline,
                ((ControlTransportFactory.Selection.Available) selection).transport());
    }

    private static ControlResult exchange(
            ControlEndpoint endpoint, ControlCommand command, OperationDeadline deadline, ControlTransport transport
    ) {
        byte[] request;
        try {
            request = new NativeControlCodec().encode(command);
        } catch (IllegalArgumentException error) {
            return failed(command, ControlResult.FailureKind.VALIDATION, error.getMessage());
        }
        ControlTransport.Exchange exchange = transport.exchange(endpoint, request, deadline);
        if (exchange instanceof ControlTransport.Exchange.Response response) {
            ControlResult decoded = new NativeControlCodec().decode(command, response.frame());
            if (decoded instanceof ControlResult.Failed failure
                    && failure.kind() == ControlResult.FailureKind.FRAMING
                    && mayHaveApplied(command)) {
                return failed(command, ControlResult.FailureKind.AMBIGUOUS_DELIVERY, failure.detail());
            }
            return decoded;
        }
        ControlTransport.Exchange.Failed failure = (ControlTransport.Exchange.Failed) exchange;
        ControlResult.FailureKind kind = failure.mayHaveDelivered()
                ? ControlResult.FailureKind.AMBIGUOUS_DELIVERY
                : failure.kind();
        return failed(command, kind, failure.detail());
    }

    private static boolean mayHaveApplied(ControlCommand command) {
        return command.operationSequence().isPresent()
                || command instanceof ControlCommand.AckJournal;
    }

    private static ControlResult failed(
            ControlCommand command,
            ControlResult.FailureKind kind,
            String detail
    ) {
        return new ControlResult.Failed(command.operationSequence(), kind, detail);
    }
}
