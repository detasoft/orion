package pro.deta.orion.agent.protocol;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.INVALID_FIELD;
import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.LIMIT_EXCEEDED;
import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.MALFORMED_CBOR;
import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.MISSING_FIELD;
import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.UNSUPPORTED_VERSION;

public final class AgentProtocolCodec {
    private static final BigInteger MAX_UNSIGNED_SHORT = BigInteger.valueOf(0xffff);
    private static final BigInteger MAX_SIGNED_INT = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger MIN_SIGNED_INT = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger MAX_SIGNED_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    private final AgentProtocolLimits limits;

    public AgentProtocolCodec(AgentProtocolLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits").agentMessageLimits();
    }

    public byte[] encode(AgentMessage message) throws AgentProtocolException {
        Objects.requireNonNull(message, "message");
        if (message instanceof AgentMessage.Unknown unknown) {
            return encodeUnknown(unknown);
        }

        CborWriter writer = new CborWriter(limits);
        switch (message) {
            case AgentMessage.Hello value -> encodeHello(writer, value);
            case AgentMessage.Welcome value -> encodeWelcome(writer, value);
            case AgentMessage.Heartbeat value -> encodeHeartbeat(writer, value);
            case AgentMessage.AgentStatus value -> encodeAgentStatus(writer, value);
            case AgentMessage.SessionStatus value -> encodeSessionStatus(writer, value);
            case AgentMessage.CommandResult value -> encodeCommandResult(writer, value);
            case AgentMessage.SessionList value -> encodeSessionList(writer, value);
            case AgentMessage.RequestSessionList ignored -> {
                writer.array(1);
                writer.unsigned(AgentMessageType.REQUEST_SESSION_LIST.code());
            }
            case AgentMessage.StartSession value -> encodeStartSession(writer, value);
            case AgentMessage.Input value -> encodeInput(writer, value);
            case AgentMessage.Resize value -> encodeResize(writer, value);
            case AgentMessage.Signal value -> encodeSignal(writer, value);
            case AgentMessage.Terminate value -> encodeTerminate(writer, value);
            case AgentMessage.SessionOpen value -> encodeSessionOpen(writer, value);
            case AgentMessage.SessionSync value -> encodeSessionSync(writer, value);
            case AgentMessage.Unknown ignored ->
                    throw new IllegalStateException("unknown message handled above");
        }
        return writer.toByteArray();
    }

    public AgentMessage decode(byte[] encoded) throws AgentProtocolException {
        Objects.requireNonNull(encoded, "encoded");
        return decode(encoded, 0, encoded.length);
    }

    AgentMessage decode(byte[] encoded, int from, int to) throws AgentProtocolException {
        Objects.requireNonNull(encoded, "encoded");
        Objects.checkFromToIndex(from, to, encoded.length);
        if (to - from > limits.maxMessageBytes()) {
            throw failure(LIMIT_EXCEEDED, "Agent protocol message exceeds configured limit");
        }
        int itemLength = CborItemScanner.itemLength(encoded, from, to, limits);
        if (itemLength < 0 || itemLength != to - from) {
            throw failure(MALFORMED_CBOR, "Agent protocol message must contain exactly one complete CBOR item");
        }

        List<CborArrayItems.Slice> items = CborArrayItems.parse(encoded, from, to, limits);
        if (items.isEmpty()) {
            throw failure(MISSING_FIELD, "Missing message type");
        }
        int typeCode = messageType(read(encoded, items.getFirst()));
        AgentMessageType type = AgentMessageType.fromCode(typeCode);
        if (type == null) {
            return new AgentMessage.Unknown(typeCode, ProtocolBytes.copyOf(encoded, from, to));
        }
        Fields fields = knownFields(encoded, items, type);

        try {
            return switch (type) {
                case HELLO -> decodeHello(fields);
                case WELCOME -> decodeWelcome(fields);
                case HEARTBEAT -> new AgentMessage.Heartbeat(
                        new AgentId(fields.text(1, "agentId")),
                        new AgentInstanceId(fields.uuid(2, "instanceId")),
                        fields.signedLong(3, "epochMillis"));
                case AGENT_STATUS -> decodeAgentStatus(fields);
                case SESSION_STATUS -> new AgentMessage.SessionStatus(
                        descriptor(fields.required(1, "session")));
                case COMMAND_RESULT -> new AgentMessage.CommandResult(
                        new CommandId(fields.text(1, "commandId")),
                        fields.optionalText(2, "sessionId").map(SessionId::new),
                        requiredEnum(
                                AgentMessage.CommandOutcome.fromWireCode(fields.unsignedShort(3, "outcome")),
                                "command outcome"),
                        fields.text(4, "detail"));
                case SESSION_LIST -> decodeSessionList(fields);
                case REQUEST_SESSION_LIST -> {
                    fields.requireMinimum(1);
                    yield new AgentMessage.RequestSessionList();
                }
                case START_SESSION -> decodeStartSession(fields);
                case INPUT -> new AgentMessage.Input(
                        new CommandId(fields.text(1, "commandId")),
                        new SessionId(fields.text(2, "sessionId")),
                        fields.uuid(3, "inputId"),
                        ProtocolBytes.copyOf(fields.bytes(4, "bytes")));
                case RESIZE -> new AgentMessage.Resize(
                        new CommandId(fields.text(1, "commandId")),
                        new SessionId(fields.text(2, "sessionId")),
                        fields.unsignedShort(3, "columns"),
                        fields.unsignedShort(4, "rows"));
                case SIGNAL -> new AgentMessage.Signal(
                        new CommandId(fields.text(1, "commandId")),
                        new SessionId(fields.text(2, "sessionId")),
                        requiredEnum(
                                AgentMessage.SignalKind.fromWireCode(fields.unsignedShort(3, "signal")),
                                "signal kind"),
                        fields.signedInt(4, "platformCode"));
                case TERMINATE -> new AgentMessage.Terminate(
                        new CommandId(fields.text(1, "commandId")),
                        new SessionId(fields.text(2, "sessionId")),
                        requiredEnum(
                                AgentMessage.TerminationMode.fromWireCode(fields.unsignedShort(3, "mode")),
                                "termination mode"),
                        fields.unsignedInt(4, "graceMillis"));
                case SESSION_OPEN -> decodeSessionOpen(fields);
                case SESSION_SYNC -> new AgentMessage.SessionSync(
                        new SessionId(fields.text(1, "sessionId")),
                        fields.optionalEventId(2, "afterEventId"));
            };
        } catch (IllegalArgumentException e) {
            throw new AgentProtocolException(INVALID_FIELD, e.getMessage(), e);
        }
    }

    private byte[] encodeUnknown(AgentMessage.Unknown message) throws AgentProtocolException {
        byte[] encoded = message.encoded().toByteArray();
        AgentMessage decoded = decode(encoded);
        if (!(decoded instanceof AgentMessage.Unknown unknown)
                || unknown.unknownTypeCode() != message.typeCode()) {
            throw failure(INVALID_FIELD, "Unknown message bytes do not match its message type");
        }
        return encoded;
    }

    private Fields knownFields(
            byte[] encoded,
            List<CborArrayItems.Slice> items,
            AgentMessageType type
    ) throws AgentProtocolException {
        int minimum = minimumFields(type);
        if (items.size() < minimum) {
            throw failure(MISSING_FIELD, type + " has fewer than " + minimum + " fields");
        }
        int known = Math.min(items.size(), maximumKnownFields(type));
        List<CborReader.Value> values = new ArrayList<>(known);
        for (int index = 0; index < known; index++) {
            values.add(read(encoded, items.get(index)));
        }
        return Fields.array(new CborReader.ArrayValue(List.copyOf(values)), type.toString());
    }

    private static int maximumKnownFields(AgentMessageType type) {
        return switch (type) {
            case HELLO -> 12;
            case WELCOME -> 6;
            default -> minimumFields(type);
        };
    }

    private CborReader.Value read(byte[] encoded, CborArrayItems.Slice slice)
            throws AgentProtocolException {
        return new CborReader(encoded, slice.from(), slice.to(), limits).readRoot();
    }

    private static int messageType(CborReader.Value value) throws AgentProtocolException {
        if (!(value instanceof CborReader.IntegerValue integer)
                || integer.value().signum() < 0
                || integer.value().compareTo(MAX_UNSIGNED_SHORT) > 0) {
            throw failure(INVALID_FIELD, "message type must fit an unsigned 16-bit integer");
        }
        return integer.value().intValue();
    }

    private static int minimumFields(AgentMessageType type) {
        return switch (type) {
            case HELLO, AGENT_STATUS -> 8;
            case WELCOME, COMMAND_RESULT, INPUT, RESIZE, SIGNAL, TERMINATE, SESSION_OPEN -> 5;
            case HEARTBEAT -> 4;
            case SESSION_SYNC -> 3;
            case SESSION_STATUS, SESSION_LIST -> 2;
            case REQUEST_SESSION_LIST -> 1;
            case START_SESSION -> 11;
        };
    }

    private void encodeHello(CborWriter writer, AgentMessage.Hello value) throws AgentProtocolException {
        requireCurrent(value.protocolVersion(), value.journalFormatVersion());
        writer.array(value.authentication().isPresent() ? 12 : 8);
        writer.unsigned(value.typeCode());
        writer.unsigned(value.protocolVersion().value());
        writer.unsigned(value.journalFormatVersion().value());
        writer.text(value.agentId().value());
        writer.uuid(value.instanceId().value());
        writer.text(value.agentVersion());
        machine(writer, value.machine());
        writer.stringMap(value.capabilities());
        if (value.authentication().isPresent()) {
            AgentAuthentication authentication = value.authentication().orElseThrow();
            writer.signed(authentication.generation().value());
            writer.uuid(authentication.launchId().value());
            writer.unsigned(authentication.kind().wireCode());
            writer.bytes(authentication.credential());
        }
    }

    private void encodeWelcome(CborWriter writer, AgentMessage.Welcome value) throws AgentProtocolException {
        requireCurrent(value.protocolVersion(), value.journalFormatVersion());
        writer.array(value.reconnectToken().isPresent() ? 6 : 5);
        writer.unsigned(value.typeCode());
        writer.unsigned(value.protocolVersion().value());
        writer.unsigned(value.journalFormatVersion().value());
        writer.text(value.connectionId().value());
        writer.stringMap(value.configuration());
        if (value.reconnectToken().isPresent()) {
            writer.bytes(value.reconnectToken().orElseThrow());
        }
    }

    private void encodeHeartbeat(CborWriter writer, AgentMessage.Heartbeat value)
            throws AgentProtocolException {
        writer.array(4);
        writer.unsigned(value.typeCode());
        writer.text(value.agentId().value());
        writer.uuid(value.instanceId().value());
        writer.signed(value.epochMillis());
    }

    private void encodeAgentStatus(CborWriter writer, AgentMessage.AgentStatus value)
            throws AgentProtocolException {
        writer.array(8);
        writer.unsigned(value.typeCode());
        writer.text(value.agentId().value());
        writer.uuid(value.instanceId().value());
        writer.text(value.agentVersion());
        machine(writer, value.machine());
        writer.unsigned(value.activeSessions());
        writer.stringMap(value.metrics());
        writer.stringMap(value.capabilities());
    }

    private void encodeSessionStatus(CborWriter writer, AgentMessage.SessionStatus value)
            throws AgentProtocolException {
        writer.array(2);
        writer.unsigned(value.typeCode());
        descriptor(writer, value.session());
    }

    private void encodeCommandResult(CborWriter writer, AgentMessage.CommandResult value)
            throws AgentProtocolException {
        writer.array(5);
        writer.unsigned(value.typeCode());
        writer.text(value.commandId().value());
        optionalText(writer, value.sessionId().map(SessionId::value));
        writer.unsigned(value.outcome().wireCode());
        writer.text(value.detail());
    }

    private void encodeSessionList(CborWriter writer, AgentMessage.SessionList value)
            throws AgentProtocolException {
        checkCollectionSize(value.sessions().size(), "sessions");
        writer.array(2);
        writer.unsigned(value.typeCode());
        writer.array(value.sessions().size());
        for (SessionDescriptor session : value.sessions()) {
            descriptor(writer, session);
        }
    }

    private void encodeStartSession(CborWriter writer, AgentMessage.StartSession value)
            throws AgentProtocolException {
        checkCollectionSize(value.command().size(), "command");
        writer.array(11);
        writer.unsigned(value.typeCode());
        writer.text(value.commandId().value());
        writer.text(value.sessionId().value());
        optionalText(writer, value.workspaceId().map(WorkspaceId::value));
        writer.array(value.command().size());
        for (String argument : value.command()) {
            writer.text(argument);
        }
        writer.text(value.workingDirectory());
        writer.stringMap(value.environment());
        writer.unsigned(value.columns());
        writer.unsigned(value.rows());
        writer.text(value.sandboxPolicy());
        writer.text(value.runtime());
    }

    private void encodeInput(CborWriter writer, AgentMessage.Input value) throws AgentProtocolException {
        writer.array(5);
        writer.unsigned(value.typeCode());
        writer.text(value.commandId().value());
        writer.text(value.sessionId().value());
        writer.uuid(value.inputId());
        writer.bytes(value.bytes());
    }

    private void encodeResize(CborWriter writer, AgentMessage.Resize value) throws AgentProtocolException {
        writer.array(5);
        writer.unsigned(value.typeCode());
        writer.text(value.commandId().value());
        writer.text(value.sessionId().value());
        writer.unsigned(value.columns());
        writer.unsigned(value.rows());
    }

    private void encodeSignal(CborWriter writer, AgentMessage.Signal value) throws AgentProtocolException {
        writer.array(5);
        writer.unsigned(value.typeCode());
        writer.text(value.commandId().value());
        writer.text(value.sessionId().value());
        writer.unsigned(value.signal().wireCode());
        writer.signed(value.platformCode());
    }

    private void encodeTerminate(CborWriter writer, AgentMessage.Terminate value)
            throws AgentProtocolException {
        writer.array(5);
        writer.unsigned(value.typeCode());
        writer.text(value.commandId().value());
        writer.text(value.sessionId().value());
        writer.unsigned(value.mode().wireCode());
        writer.signed(value.graceMillis());
    }

    private void encodeSessionOpen(CborWriter writer, AgentMessage.SessionOpen value)
            throws AgentProtocolException {
        writer.array(5);
        writer.unsigned(value.typeCode());
        writer.text(value.sessionId().value());
        optionalEventId(writer, value.firstAvailableEventId());
        optionalEventId(writer, value.lastAvailableEventId());
        writer.unsigned(value.state().wireCode());
    }

    private void encodeSessionSync(CborWriter writer, AgentMessage.SessionSync value)
            throws AgentProtocolException {
        writer.array(3);
        writer.unsigned(value.typeCode());
        writer.text(value.sessionId().value());
        optionalEventId(writer, value.afterEventId());
    }

    private AgentMessage.Hello decodeHello(Fields fields) throws AgentProtocolException {
        AgentProtocolVersion protocol = new AgentProtocolVersion(fields.unsignedShort(1, "protocolVersion"));
        JournalFormatVersion journal = new JournalFormatVersion(
                fields.unsignedShort(2, "journalFormatVersion"));
        requireCurrent(protocol, journal);
        if (fields.size() > 8 && fields.size() < 12) {
            throw failure(MISSING_FIELD, "HELLO authentication tail must contain four fields");
        }
        Optional<AgentAuthentication> authentication = fields.size() < 12
                ? Optional.empty()
                : Optional.of(new AgentAuthentication(
                        new AgentGeneration(fields.signedLong(8, "generation")),
                        new AgentLaunchId(fields.uuid(9, "launchId")),
                        requiredEnum(AgentAuthentication.Kind.fromWireCode(
                                fields.unsignedShort(10, "credentialKind")), "authentication kind"),
                        ProtocolBytes.copyOf(fields.bytes(11, "credential"))));
        return new AgentMessage.Hello(
                protocol,
                journal,
                new AgentId(fields.text(3, "agentId")),
                new AgentInstanceId(fields.uuid(4, "instanceId")),
                fields.text(5, "agentVersion"),
                machine(fields.required(6, "machine")),
                fields.stringMap(7, "capabilities"),
                authentication);
    }

    private AgentMessage.Welcome decodeWelcome(Fields fields) throws AgentProtocolException {
        AgentProtocolVersion protocol = new AgentProtocolVersion(fields.unsignedShort(1, "protocolVersion"));
        JournalFormatVersion journal = new JournalFormatVersion(
                fields.unsignedShort(2, "journalFormatVersion"));
        requireCurrent(protocol, journal);
        return new AgentMessage.Welcome(
                protocol,
                journal,
                new ConnectionId(fields.text(3, "connectionId")),
                fields.stringMap(4, "configuration"),
                fields.size() < 6
                        ? Optional.empty()
                        : Optional.of(ProtocolBytes.copyOf(fields.bytes(5, "reconnectToken"))));
    }

    private AgentMessage.AgentStatus decodeAgentStatus(Fields fields) throws AgentProtocolException {
        return new AgentMessage.AgentStatus(
                new AgentId(fields.text(1, "agentId")),
                new AgentInstanceId(fields.uuid(2, "instanceId")),
                fields.text(3, "agentVersion"),
                machine(fields.required(4, "machine")),
                fields.nonNegativeInt(5, "activeSessions"),
                fields.stringMap(6, "metrics"),
                fields.stringMap(7, "capabilities"));
    }

    private AgentMessage.SessionList decodeSessionList(Fields fields) throws AgentProtocolException {
        CborReader.ArrayValue sessions = Fields.array(fields.required(1, "sessions"), "sessions").array;
        List<SessionDescriptor> decoded = new ArrayList<>(sessions.values().size());
        for (CborReader.Value value : sessions.values()) {
            decoded.add(descriptor(value));
        }
        return new AgentMessage.SessionList(decoded);
    }

    private AgentMessage.StartSession decodeStartSession(Fields fields) throws AgentProtocolException {
        return new AgentMessage.StartSession(
                new CommandId(fields.text(1, "commandId")),
                new SessionId(fields.text(2, "sessionId")),
                fields.optionalText(3, "workspaceId").map(WorkspaceId::new),
                fields.textList(4, "command"),
                fields.text(5, "workingDirectory"),
                fields.stringMap(6, "environment"),
                fields.unsignedShort(7, "columns"),
                fields.unsignedShort(8, "rows"),
                fields.text(9, "sandboxPolicy"),
                fields.text(10, "runtime"));
    }

    private AgentMessage.SessionOpen decodeSessionOpen(Fields fields) throws AgentProtocolException {
        return new AgentMessage.SessionOpen(
                new SessionId(fields.text(1, "sessionId")),
                fields.optionalEventId(2, "firstAvailableEventId"),
                fields.optionalEventId(3, "lastAvailableEventId"),
                requiredEnum(
                        AgentMessage.SessionState.fromWireCode(fields.unsignedShort(4, "state")),
                        "session state"));
    }

    private void machine(CborWriter writer, MachineInfo machine) throws AgentProtocolException {
        writer.array(3);
        writer.text(machine.hostname());
        writer.text(machine.operatingSystem());
        writer.text(machine.architecture());
    }

    private MachineInfo machine(CborReader.Value value) throws AgentProtocolException {
        Fields fields = Fields.array(value, "machine");
        return new MachineInfo(
                fields.text(0, "hostname"),
                fields.text(1, "operatingSystem"),
                fields.text(2, "architecture"));
    }

    private void descriptor(CborWriter writer, SessionDescriptor descriptor) throws AgentProtocolException {
        writer.array(5);
        writer.text(descriptor.sessionId().value());
        writer.unsigned(descriptor.state().wireCode());
        optionalEventId(writer, descriptor.firstAvailableEventId());
        optionalEventId(writer, descriptor.lastAvailableEventId());
        writer.text(descriptor.detail());
    }

    private SessionDescriptor descriptor(CborReader.Value value) throws AgentProtocolException {
        Fields fields = Fields.array(value, "session descriptor");
        return new SessionDescriptor(
                new SessionId(fields.text(0, "sessionId")),
                requiredEnum(
                        AgentMessage.SessionState.fromWireCode(fields.unsignedShort(1, "state")),
                        "session state"),
                fields.optionalEventId(2, "firstAvailableEventId"),
                fields.optionalEventId(3, "lastAvailableEventId"),
                fields.text(4, "detail"));
    }

    private void optionalText(CborWriter writer, Optional<String> value) throws AgentProtocolException {
        if (value.isPresent()) {
            writer.text(value.orElseThrow());
        } else {
            writer.nullValue();
        }
    }

    private void optionalEventId(CborWriter writer, Optional<EventId> value) {
        if (value.isPresent()) {
            writer.unsigned(value.orElseThrow());
        } else {
            writer.nullValue();
        }
    }

    private void requireCurrent(AgentProtocolVersion protocol, JournalFormatVersion journal)
            throws AgentProtocolException {
        if (!AgentProtocolVersion.CURRENT.equals(protocol)) {
            throw failure(UNSUPPORTED_VERSION, "Unsupported Agent protocol version: " + protocol.value());
        }
        if (!JournalFormatVersion.CURRENT.equals(journal)) {
            throw failure(UNSUPPORTED_VERSION, "Unsupported session journal version: " + journal.value());
        }
    }

    private void checkCollectionSize(int size, String name) throws AgentProtocolException {
        if (size > limits.maxCollectionEntries()) {
            throw failure(LIMIT_EXCEEDED, name + " exceeds configured entry limit");
        }
    }

    private static <T> T requiredEnum(T value, String name) throws AgentProtocolException {
        if (value == null) {
            throw failure(INVALID_FIELD, "Unknown " + name);
        }
        return value;
    }

    private static AgentProtocolException failure(AgentProtocolException.Reason reason, String message) {
        return new AgentProtocolException(reason, message);
    }

    private static final class Fields {
        private final CborReader.ArrayValue array;

        private Fields(CborReader.ArrayValue array) {
            this.array = array;
        }

        static Fields array(CborReader.Value value, String name) throws AgentProtocolException {
            if (!(value instanceof CborReader.ArrayValue array)) {
                throw failure(INVALID_FIELD, name + " must be a CBOR array");
            }
            return new Fields(array);
        }

        CborReader.Value required(int index, String name) throws AgentProtocolException {
            if (index >= array.values().size()) {
                throw failure(MISSING_FIELD, "Missing " + name);
            }
            return array.values().get(index);
        }

        void requireMinimum(int count) throws AgentProtocolException {
            if (array.values().size() < count) {
                throw failure(MISSING_FIELD, "Message has fewer than " + count + " fields");
            }
        }

        int size() {
            return array.values().size();
        }

        String text(int index, String name) throws AgentProtocolException {
            CborReader.Value value = required(index, name);
            if (!(value instanceof CborReader.TextValue text)) {
                throw failure(INVALID_FIELD, name + " must be a CBOR text string");
            }
            return text.value();
        }

        Optional<String> optionalText(int index, String name) throws AgentProtocolException {
            CborReader.Value value = required(index, name);
            if (value == CborReader.NullValue.INSTANCE) {
                return Optional.empty();
            }
            if (!(value instanceof CborReader.TextValue text)) {
                throw failure(INVALID_FIELD, name + " must be a CBOR text string or null");
            }
            return Optional.of(text.value());
        }

        byte[] bytes(int index, String name) throws AgentProtocolException {
            CborReader.Value value = required(index, name);
            if (!(value instanceof CborReader.BytesValue binary)) {
                throw failure(INVALID_FIELD, name + " must be a CBOR byte string");
            }
            return java.util.Arrays.copyOf(binary.value(), binary.value().length);
        }

        UUID uuid(int index, String name) throws AgentProtocolException {
            byte[] value = bytes(index, name);
            if (value.length != 16) {
                throw failure(INVALID_FIELD, name + " must contain exactly 16 bytes");
            }
            return new UUID(readLong(value, 0), readLong(value, 8));
        }

        int unsignedShort(int index, String name) throws AgentProtocolException {
            BigInteger value = integer(index, name);
            if (value.signum() < 0 || value.compareTo(MAX_UNSIGNED_SHORT) > 0) {
                throw failure(INVALID_FIELD, name + " must fit an unsigned 16-bit integer");
            }
            return value.intValue();
        }

        int nonNegativeInt(int index, String name) throws AgentProtocolException {
            BigInteger value = integer(index, name);
            if (value.signum() < 0 || value.compareTo(MAX_SIGNED_INT) > 0) {
                throw failure(INVALID_FIELD, name + " must fit a non-negative signed integer");
            }
            return value.intValue();
        }

        int signedInt(int index, String name) throws AgentProtocolException {
            BigInteger value = integer(index, name);
            if (value.compareTo(MIN_SIGNED_INT) < 0 || value.compareTo(MAX_SIGNED_INT) > 0) {
                throw failure(INVALID_FIELD, name + " must fit a signed 32-bit integer");
            }
            return value.intValue();
        }

        long unsignedInt(int index, String name) throws AgentProtocolException {
            BigInteger value = integer(index, name);
            if (value.signum() < 0 || value.bitLength() > Integer.SIZE) {
                throw failure(INVALID_FIELD, name + " must fit an unsigned 32-bit integer");
            }
            return value.longValue();
        }

        long signedLong(int index, String name) throws AgentProtocolException {
            BigInteger value = integer(index, name);
            if (value.signum() < 0 || value.compareTo(MAX_SIGNED_LONG) > 0) {
                throw failure(INVALID_FIELD, name + " must fit a non-negative signed 64-bit integer");
            }
            return value.longValue();
        }

        Optional<EventId> optionalEventId(int index, String name) throws AgentProtocolException {
            CborReader.Value value = required(index, name);
            if (value == CborReader.NullValue.INSTANCE) {
                return Optional.empty();
            }
            if (!(value instanceof CborReader.IntegerValue integer)) {
                throw failure(INVALID_FIELD, name + " must be an unsigned integer or null");
            }
            try {
                return Optional.of(EventId.fromUnsigned(integer.value()));
            } catch (IllegalArgumentException e) {
                throw new AgentProtocolException(INVALID_FIELD, e.getMessage(), e);
            }
        }

        List<String> textList(int index, String name) throws AgentProtocolException {
            Fields values = array(required(index, name), name);
            List<String> result = new ArrayList<>(values.array.values().size());
            for (int valueIndex = 0; valueIndex < values.array.values().size(); valueIndex++) {
                result.add(values.text(valueIndex, name + " entry"));
            }
            return List.copyOf(result);
        }

        Map<String, String> stringMap(int index, String name) throws AgentProtocolException {
            CborReader.Value value = required(index, name);
            if (!(value instanceof CborReader.MapValue map)) {
                throw failure(INVALID_FIELD, name + " must be a CBOR map");
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (CborReader.MapEntry entry : map.entries()) {
                if (!(entry.key() instanceof CborReader.TextValue key)
                        || !(entry.value() instanceof CborReader.TextValue mapValue)) {
                    throw failure(INVALID_FIELD, name + " keys and values must be text strings");
                }
                if (result.put(key.value(), mapValue.value()) != null) {
                    throw failure(INVALID_FIELD, name + " contains a duplicate key: " + key.value());
                }
            }
            return Map.copyOf(result);
        }

        private BigInteger integer(int index, String name) throws AgentProtocolException {
            CborReader.Value value = required(index, name);
            if (!(value instanceof CborReader.IntegerValue integer)) {
                throw failure(INVALID_FIELD, name + " must be a CBOR integer");
            }
            return integer.value();
        }

        private static long readLong(byte[] value, int offset) {
            long result = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                result = (result << Byte.SIZE) | (value[offset + index] & 0xffL);
            }
            return result;
        }
    }
}
