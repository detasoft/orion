package pro.deta.orion.agentd.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public sealed interface AgentMessage {
    int typeCode();

    record Hello(
            AgentProtocolVersion protocolVersion,
            AgentId agentId,
            InstanceId instanceId,
            String agentVersion,
            MachineInfo machineInfo,
            Map<String, String> capabilities
    ) implements AgentMessage {
        public Hello {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(instanceId, "instanceId");
            agentVersion = ProtocolValidation.nonBlank(agentVersion, "agentVersion");
            Objects.requireNonNull(machineInfo, "machineInfo");
            capabilities = ProtocolValidation.stringMap(capabilities, "capabilities");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.HELLO.code();
        }
    }

    record Welcome(
            AgentProtocolVersion protocolVersion,
            ConnectionId connectionId,
            Map<String, String> configuration
    ) implements AgentMessage {
        public Welcome {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(connectionId, "connectionId");
            configuration = ProtocolValidation.stringMap(configuration, "configuration");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.WELCOME.code();
        }
    }

    record Heartbeat(AgentId agentId, InstanceId instanceId, long sentAtEpochMillis) implements AgentMessage {
        public Heartbeat {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(instanceId, "instanceId");
            if (sentAtEpochMillis <= 0) {
                throw new IllegalArgumentException("sentAtEpochMillis must be positive");
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.HEARTBEAT.code();
        }
    }

    record AgentStatus(
            AgentId agentId,
            InstanceId instanceId,
            String agentVersion,
            MachineInfo machineInfo,
            int activeSessions,
            Map<String, String> metrics,
            Map<String, String> capabilities
    ) implements AgentMessage {
        public AgentStatus {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(instanceId, "instanceId");
            agentVersion = ProtocolValidation.nonBlank(agentVersion, "agentVersion");
            Objects.requireNonNull(machineInfo, "machineInfo");
            if (activeSessions < 0) {
                throw new IllegalArgumentException("activeSessions must not be negative");
            }
            metrics = ProtocolValidation.stringMap(metrics, "metrics");
            capabilities = ProtocolValidation.stringMap(capabilities, "capabilities");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.AGENT_STATUS.code();
        }
    }

    record SessionStatus(SessionId sessionId, SessionState state, String detail) implements AgentMessage {
        public SessionStatus {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNull(detail, "detail");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SESSION_STATUS.code();
        }
    }

    record CommandResult(
            CommandId commandId,
            Optional<SessionId> sessionId,
            CommandOutcome outcome,
            String detail
    ) implements AgentMessage {
        public CommandResult {
            Objects.requireNonNull(commandId, "commandId");
            sessionId = ProtocolValidation.optional(sessionId, "sessionId");
            Objects.requireNonNull(outcome, "outcome");
            detail = Objects.requireNonNull(detail, "detail");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.COMMAND_RESULT.code();
        }
    }

    record StartSession(
            CommandId commandId,
            SessionId sessionId,
            Optional<WorkspaceId> workspaceId,
            List<String> command,
            String workingDirectory,
            Map<String, String> environment,
            int columns,
            int rows,
            String sandboxPolicy,
            String runtime
    ) implements AgentMessage {
        public StartSession {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            workspaceId = ProtocolValidation.optional(workspaceId, "workspaceId");
            command = ProtocolValidation.command(command);
            workingDirectory = ProtocolValidation.nonBlank(workingDirectory, "workingDirectory");
            environment = ProtocolValidation.stringMap(environment, "environment");
            columns = ProtocolValidation.terminalDimension(columns, "columns");
            rows = ProtocolValidation.terminalDimension(rows, "rows");
            sandboxPolicy = ProtocolValidation.nonBlank(sandboxPolicy, "sandboxPolicy");
            runtime = ProtocolValidation.nonBlank(runtime, "runtime");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.START_SESSION.code();
        }
    }

    record Input(
            CommandId commandId,
            SessionId sessionId,
            UUID inputId,
            ProtocolBytes bytes
    ) implements AgentMessage {
        public Input {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.INPUT.code();
        }
    }

    record Resize(CommandId commandId, SessionId sessionId, int columns, int rows) implements AgentMessage {
        public Resize {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            columns = ProtocolValidation.terminalDimension(columns, "columns");
            rows = ProtocolValidation.terminalDimension(rows, "rows");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.RESIZE.code();
        }
    }

    record Signal(
            CommandId commandId,
            SessionId sessionId,
            SignalKind signal,
            int platformCode
    ) implements AgentMessage {
        public Signal {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(signal, "signal");
            if (signal == SignalKind.PLATFORM && platformCode == -1) {
                throw new IllegalArgumentException("a platform-specific signal requires a platform code");
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SIGNAL.code();
        }
    }

    record Terminate(
            CommandId commandId,
            SessionId sessionId,
            TerminationMode mode,
            long graceMillis
    ) implements AgentMessage {
        public Terminate {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(mode, "mode");
            graceMillis = ProtocolValidation.unsignedInt(graceMillis, "graceMillis");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.TERMINATE.code();
        }
    }

    record SessionEvents(SessionId sessionId, List<SessionEventEnvelope> events) implements AgentMessage {
        public SessionEvents {
            Objects.requireNonNull(sessionId, "sessionId");
            events = List.copyOf(events);
            if (events.isEmpty()) {
                throw new IllegalArgumentException("events must not be empty");
            }
            long previousTimestamp = 0;
            for (SessionEventEnvelope event : events) {
                Objects.requireNonNull(event, "event");
                if (event.sourceTimestamp().value() <= previousTimestamp) {
                    throw new IllegalArgumentException("event timestamps must be strictly increasing");
                }
                previousTimestamp = event.sourceTimestamp().value();
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SESSION_EVENTS.code();
        }
    }

    record SessionAck(SessionId sessionId, JournalCursor through) implements AgentMessage {
        public SessionAck {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(through, "through");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SESSION_ACK.code();
        }
    }

    record ResumeSession(SessionId sessionId, JournalCursor after) implements AgentMessage {
        public ResumeSession {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(after, "after");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.RESUME_SESSION.code();
        }
    }

    record SessionGap(
            SessionId sessionId,
            JournalCursor requested,
            SessionTimestamp availableFrom
    ) implements AgentMessage {
        public SessionGap {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(requested, "requested");
            Objects.requireNonNull(availableFrom, "availableFrom");
            if (availableFrom.value() <= requested.timestamp()) {
                throw new IllegalArgumentException("availableFrom must be later than the requested cursor");
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SESSION_GAP.code();
        }
    }

    record Unknown(int unknownTypeCode, ProtocolBytes payload) implements AgentMessage {
        public Unknown {
            ProtocolValidation.unsignedShort(unknownTypeCode, "unknownTypeCode");
            if (AgentMessageType.fromCode(unknownTypeCode) != null) {
                throw new IllegalArgumentException("Known message type cannot be represented as unknown");
            }
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public int typeCode() {
            return unknownTypeCode;
        }
    }

    enum SessionState {
        STARTING(1),
        RUNNING(2),
        EXITED(3),
        DEGRADED(4),
        JOURNAL_GAP(5),
        LOST(6),
        FAILED(7);

        private final int wireCode;

        SessionState(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        public static SessionState fromWireCode(int wireCode) {
            for (SessionState value : values()) {
                if (value.wireCode == wireCode) {
                    return value;
                }
            }
            return null;
        }
    }

    enum CommandOutcome {
        SUCCEEDED(1),
        FAILED(2),
        REJECTED(3),
        DUPLICATE(4);

        private final int wireCode;

        CommandOutcome(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        public static CommandOutcome fromWireCode(int wireCode) {
            for (CommandOutcome value : values()) {
                if (value.wireCode == wireCode) {
                    return value;
                }
            }
            return null;
        }
    }

    enum SignalKind {
        INTERRUPT(1),
        TERMINATE(2),
        KILL(3),
        HANGUP(4),
        QUIT(5),
        PLATFORM(0xffff);

        private final int wireCode;

        SignalKind(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        public static SignalKind fromWireCode(int wireCode) {
            for (SignalKind value : values()) {
                if (value.wireCode == wireCode) {
                    return value;
                }
            }
            return null;
        }
    }

    enum TerminationMode {
        GRACEFUL(0),
        FORCE(1);

        private final int wireCode;

        TerminationMode(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        public static TerminationMode fromWireCode(int wireCode) {
            for (TerminationMode value : values()) {
                if (value.wireCode == wireCode) {
                    return value;
                }
            }
            return null;
        }
    }
}
