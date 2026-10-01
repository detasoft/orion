package pro.deta.orion.agentd.session;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentMessageRecord;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Makes one native delivery attempt; its caller serializes commands for each session. */
public final class EstablishedSessionCommandDelivery {
    private final SessionRegistry registry;
    private final SessionControlClient client;

    public EstablishedSessionCommandDelivery(SessionRegistry registry, SessionControlClient client) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.client = Objects.requireNonNull(client, "client");
    }

    public ControlResult deliver(AgentMessageRecord record) {
        Objects.requireNonNull(record, "record");
        AgentMessage message = record.message();
        OptionalLong sequence = sequence(message);
        if (sequence.isEmpty()) {
            return failed(sequence, ControlResult.FailureKind.VALIDATION,
                    "message is not an established-session command");
        }
        String sessionId = sessionId(message);
        LocalSession session = registry.snapshot().sessions().get(sessionId);
        if (session == null) {
            return failed(sequence, ControlResult.FailureKind.CONNECTION, "session is not discovered");
        }
        if (session.state() != LocalSessionState.LIVE
                || session.host().status() != HostObservation.Status.LIVE
                || !session.journal().readable()) {
            return failed(sequence, ControlResult.FailureKind.CONNECTION, "session is unavailable");
        }

        ControlCommand command;
        try {
            command = nativeCommand(message, record);
        } catch (IllegalArgumentException failure) {
            return failed(sequence, ControlResult.FailureKind.VALIDATION, failure.getMessage());
        }
        ControlEndpoint endpoint = session.manifest().control();
        ControlResult claim = client.send(endpoint, new ControlCommand.ClaimServerControl());
        if (!(claim instanceof ControlResult.ServerControlClaimed)) {
            if (claim instanceof ControlResult.Failed failure) {
                return failed(sequence, failure.kind(), "server control claim failed: " + failure.detail());
            }
            if (claim instanceof ControlResult.Rejected rejection) {
                return failed(sequence, ControlResult.FailureKind.CONNECTION,
                        "server control claim rejected: " + rejection.detail());
            }
            return failed(sequence, ControlResult.FailureKind.FRAMING,
                    "native host returned an unexpected server control claim response");
        }
        return client.send(endpoint, command);
    }

    private static ControlCommand nativeCommand(AgentMessage message, AgentMessageRecord record) {
        Optional<ProtocolBytes> envelope = Optional.of(record.encodedItem());
        return switch (message) {
            case AgentMessage.Input input -> new ControlCommand.Input(
                    input.operationSequence(), SessionCommandSource.SERVER, envelope,
                    input.inputId(), input.bytes());
            case AgentMessage.Resize resize -> new ControlCommand.Resize(
                    resize.operationSequence(), SessionCommandSource.SERVER, envelope,
                    resize.columns(), resize.rows());
            case AgentMessage.Signal signal -> new ControlCommand.Signal(
                    signal.operationSequence(), SessionCommandSource.SERVER, envelope,
                    signal.signal(), signal.platformCode(), OptionalLong.empty());
            case AgentMessage.Terminate terminate -> new ControlCommand.Terminate(
                    terminate.operationSequence(), SessionCommandSource.SERVER, envelope,
                    terminate.mode());
            default -> throw new IllegalArgumentException("message is not an established-session command");
        };
    }

    private static OptionalLong sequence(AgentMessage message) {
        return switch (message) {
            case AgentMessage.Input input -> OptionalLong.of(input.operationSequence());
            case AgentMessage.Resize resize -> OptionalLong.of(resize.operationSequence());
            case AgentMessage.Signal signal -> OptionalLong.of(signal.operationSequence());
            case AgentMessage.Terminate terminate -> OptionalLong.of(terminate.operationSequence());
            default -> OptionalLong.empty();
        };
    }

    private static String sessionId(AgentMessage message) {
        return switch (message) {
            case AgentMessage.Input input -> input.sessionId().value();
            case AgentMessage.Resize resize -> resize.sessionId().value();
            case AgentMessage.Signal signal -> signal.sessionId().value();
            case AgentMessage.Terminate terminate -> terminate.sessionId().value();
            default -> throw new IllegalArgumentException("message is not an established-session command");
        };
    }

    private static ControlResult.Failed failed(
            OptionalLong sequence, ControlResult.FailureKind kind, String detail
    ) {
        return new ControlResult.Failed(sequence, kind, detail);
    }
}
