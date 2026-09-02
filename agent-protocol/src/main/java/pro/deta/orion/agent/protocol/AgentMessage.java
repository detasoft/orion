package pro.deta.orion.agent.protocol;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public sealed interface AgentMessage permits AgentMessage.Hello, AgentMessage.Welcome,
        AgentMessage.Heartbeat, AgentMessage.AgentStatus, AgentMessage.SessionStatus,
        AgentMessage.CommandResult, AgentMessage.SessionList, AgentMessage.RequestSessionList,
        AgentMessage.StartSession, AgentMessage.Input, AgentMessage.Resize, AgentMessage.Signal,
        AgentMessage.Terminate, AgentMessage.SessionOpen, AgentMessage.SessionSync, AgentMessage.Unknown {

    int typeCode();

    record Hello(
            AgentProtocolVersion protocolVersion,
            JournalFormatVersion journalFormatVersion,
            AgentId agentId,
            AgentInstanceId instanceId,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            Optional<AgentAuthentication> authentication
    ) implements AgentMessage {
        public Hello {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(journalFormatVersion, "journalFormatVersion");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(instanceId, "instanceId");
            agentVersion = ProtocolValidation.nonBlank(agentVersion, "agentVersion");
            Objects.requireNonNull(machine, "machine");
            capabilities = ProtocolValidation.stringMap(capabilities, "capabilities");
            authentication = ProtocolValidation.optional(authentication, "authentication");
        }

        public Hello(AgentProtocolVersion protocolVersion, JournalFormatVersion journalFormatVersion,
                     AgentId agentId, AgentInstanceId instanceId, String agentVersion,
                     MachineInfo machine, Map<String, String> capabilities) {
            this(protocolVersion, journalFormatVersion, agentId, instanceId, agentVersion,
                    machine, capabilities, Optional.empty());
        }

        @Override
        public int typeCode() {
            return AgentMessageType.HELLO.code();
        }
    }

    record Welcome(
            AgentProtocolVersion protocolVersion,
            JournalFormatVersion journalFormatVersion,
            ConnectionId connectionId,
            Map<String, String> configuration,
            Optional<ProtocolBytes> reconnectToken
    ) implements AgentMessage {
        public Welcome {
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(journalFormatVersion, "journalFormatVersion");
            Objects.requireNonNull(connectionId, "connectionId");
            configuration = ProtocolValidation.stringMap(configuration, "configuration");
            reconnectToken = ProtocolValidation.optional(reconnectToken, "reconnectToken");
            reconnectToken.ifPresent(token -> ProtocolValidation.byteLength(
                    token.size(), AgentAuthentication.MIN_CREDENTIAL_BYTES,
                    AgentAuthentication.MAX_CREDENTIAL_BYTES, "reconnect token"));
        }

        public Welcome(AgentProtocolVersion protocolVersion, JournalFormatVersion journalFormatVersion,
                       ConnectionId connectionId, Map<String, String> configuration) {
            this(protocolVersion, journalFormatVersion, connectionId, configuration, Optional.empty());
        }

        @Override
        public int typeCode() {
            return AgentMessageType.WELCOME.code();
        }
    }

    record Heartbeat(AgentId agentId, AgentInstanceId instanceId, long epochMillis) implements AgentMessage {
        public Heartbeat {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(instanceId, "instanceId");
            if (epochMillis < 0) {
                throw new IllegalArgumentException("epochMillis must not be negative");
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.HEARTBEAT.code();
        }
    }

    record AgentStatus(
            AgentId agentId,
            AgentInstanceId instanceId,
            String agentVersion,
            MachineInfo machine,
            int activeSessions,
            Map<String, String> metrics,
            Map<String, String> capabilities
    ) implements AgentMessage {
        public AgentStatus {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(instanceId, "instanceId");
            agentVersion = ProtocolValidation.nonBlank(agentVersion, "agentVersion");
            Objects.requireNonNull(machine, "machine");
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

    record SessionStatus(SessionDescriptor session) implements AgentMessage {
        public SessionStatus {
            Objects.requireNonNull(session, "session");
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

    record SessionList(List<SessionDescriptor> sessions) implements AgentMessage {
        public SessionList {
            sessions = List.copyOf(sessions);
            Set<SessionId> identifiers = new HashSet<>();
            for (SessionDescriptor session : sessions) {
                Objects.requireNonNull(session, "session");
                if (!identifiers.add(session.sessionId())) {
                    throw new IllegalArgumentException("sessions contains a duplicate session ID");
                }
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SESSION_LIST.code();
        }
    }

    record RequestSessionList() implements AgentMessage {
        @Override
        public int typeCode() {
            return AgentMessageType.REQUEST_SESSION_LIST.code();
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

    record Input(CommandId commandId, SessionId sessionId, UUID inputId, ProtocolBytes bytes)
            implements AgentMessage {
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

    record Signal(CommandId commandId, SessionId sessionId, SignalKind signal, int platformCode)
            implements AgentMessage {
        public Signal {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(signal, "signal");
            if (signal == SignalKind.PLATFORM && platformCode < 0) {
                throw new IllegalArgumentException(
                        "a platform-specific signal requires a non-negative platform code");
            }
            if (signal != SignalKind.PLATFORM && platformCode != -1) {
                throw new IllegalArgumentException("portable signals must use platform code -1");
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SIGNAL.code();
        }
    }

    record Terminate(CommandId commandId, SessionId sessionId, TerminationMode mode, long graceMillis)
            implements AgentMessage {
        public Terminate {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(mode, "mode");
            if (graceMillis < 0 || graceMillis > 0xffff_ffffL) {
                throw new IllegalArgumentException("graceMillis must fit an unsigned 32-bit integer");
            }
        }

        @Override
        public int typeCode() {
            return AgentMessageType.TERMINATE.code();
        }
    }

    record SessionOpen(
            SessionId sessionId,
            Optional<EventId> firstAvailableEventId,
            Optional<EventId> lastAvailableEventId,
            SessionState state
    ) implements AgentMessage {
        public SessionOpen {
            Objects.requireNonNull(sessionId, "sessionId");
            firstAvailableEventId = ProtocolValidation.optional(firstAvailableEventId, "firstAvailableEventId");
            lastAvailableEventId = ProtocolValidation.optional(lastAvailableEventId, "lastAvailableEventId");
            Objects.requireNonNull(state, "state");
            SessionDescriptor.validateRange(firstAvailableEventId, lastAvailableEventId);
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SESSION_OPEN.code();
        }
    }

    record SessionSync(SessionId sessionId, Optional<EventId> afterEventId) implements AgentMessage {
        public SessionSync {
            Objects.requireNonNull(sessionId, "sessionId");
            afterEventId = ProtocolValidation.optional(afterEventId, "afterEventId");
        }

        @Override
        public int typeCode() {
            return AgentMessageType.SESSION_SYNC.code();
        }
    }

    record Unknown(int unknownTypeCode, ProtocolBytes encoded) implements AgentMessage {
        public Unknown {
            ProtocolValidation.unsignedShort(unknownTypeCode, "unknownTypeCode");
            if (AgentMessageType.fromCode(unknownTypeCode) != null) {
                throw new IllegalArgumentException("known message type cannot be represented as unknown");
            }
            Objects.requireNonNull(encoded, "encoded");
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
