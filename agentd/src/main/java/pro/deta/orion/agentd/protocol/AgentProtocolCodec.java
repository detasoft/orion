package pro.deta.orion.agentd.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static pro.deta.orion.agentd.protocol.AgentProtocolException.Reason.DUPLICATE_FIELD;
import static pro.deta.orion.agentd.protocol.AgentProtocolException.Reason.INVALID_FIELD;
import static pro.deta.orion.agentd.protocol.AgentProtocolException.Reason.LIMIT_EXCEEDED;
import static pro.deta.orion.agentd.protocol.AgentProtocolException.Reason.MALFORMED_FRAME;
import static pro.deta.orion.agentd.protocol.AgentProtocolException.Reason.MISSING_FIELD;
import static pro.deta.orion.agentd.protocol.AgentProtocolException.Reason.UNSUPPORTED_VERSION;

public final class AgentProtocolCodec {
    private static final int MAGIC = 0x4f414750;
    private static final int FRAMING_VERSION = 1;
    private static final int FRAME_HEADER_BYTES = 16;
    private static final int FIELD_HEADER_BYTES = 6;

    private final AgentProtocolLimits limits;

    public AgentProtocolCodec(AgentProtocolLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public byte[] encode(AgentMessage message) throws AgentProtocolException {
        Objects.requireNonNull(message, "message");
        byte[] payload = encodePayload(message);
        int frameLength = FRAME_HEADER_BYTES + payload.length;
        if (frameLength > limits.maxFrameBytes()) {
            throw new AgentProtocolException(LIMIT_EXCEEDED, "Agent protocol frame exceeds configured limit");
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(frameLength);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeShort(FRAMING_VERSION);
            output.writeShort(AgentProtocolVersion.CURRENT.value());
            output.writeShort(message.typeCode());
            output.writeShort(0);
            output.writeInt(payload.length);
            output.write(payload);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("Byte array output failed", impossible);
        }
    }

    public AgentMessage decode(byte[] frame) throws AgentProtocolException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < FRAME_HEADER_BYTES) {
            throw new AgentProtocolException(MALFORMED_FRAME, "Agent protocol frame header is incomplete");
        }
        if (frame.length > limits.maxFrameBytes()) {
            throw new AgentProtocolException(LIMIT_EXCEEDED, "Agent protocol frame exceeds configured limit");
        }

        ByteBuffer input = ByteBuffer.wrap(frame);
        if (input.getInt() != MAGIC) {
            throw new AgentProtocolException(MALFORMED_FRAME, "Agent protocol frame has invalid magic");
        }
        int framingVersion = Short.toUnsignedInt(input.getShort());
        if (framingVersion != FRAMING_VERSION) {
            throw new AgentProtocolException(UNSUPPORTED_VERSION,
                    "Unsupported Agent protocol framing version: " + framingVersion);
        }
        int protocolVersion = Short.toUnsignedInt(input.getShort());
        requireCurrentVersion(protocolVersion);
        int typeCode = Short.toUnsignedInt(input.getShort());
        int flags = Short.toUnsignedInt(input.getShort());
        if (flags != 0) {
            throw new AgentProtocolException(MALFORMED_FRAME, "Agent protocol frame flags must be zero");
        }
        long payloadLength = Integer.toUnsignedLong(input.getInt());
        if (payloadLength > limits.maxFrameBytes() - FRAME_HEADER_BYTES) {
            throw new AgentProtocolException(LIMIT_EXCEEDED, "Agent protocol payload exceeds configured limit");
        }
        if (payloadLength != input.remaining()) {
            throw new AgentProtocolException(
                    MALFORMED_FRAME,
                    "Agent protocol payload length does not match frame");
        }

        byte[] payload = new byte[(int) payloadLength];
        input.get(payload);
        AgentMessageType type = AgentMessageType.fromCode(typeCode);
        if (type == null) {
            return new AgentMessage.Unknown(typeCode, ProtocolBytes.copyOf(payload));
        }

        try {
            return decodeKnown(type, new FieldReader(payload));
        } catch (IllegalArgumentException invalidValue) {
            throw new AgentProtocolException(INVALID_FIELD, "Agent protocol message contains an invalid value",
                    invalidValue);
        }
    }

    private byte[] encodePayload(AgentMessage message) throws AgentProtocolException {
        if (message instanceof AgentMessage.Unknown unknown) {
            return checkedBinary(unknown.payload());
        }

        FieldWriter fields = new FieldWriter();
        switch (message) {
            case AgentMessage.Hello value -> encodeHello(fields, value);
            case AgentMessage.Welcome value -> encodeWelcome(fields, value);
            case AgentMessage.Heartbeat value -> encodeHeartbeat(fields, value);
            case AgentMessage.AgentStatus value -> encodeAgentStatus(fields, value);
            case AgentMessage.SessionStatus value -> encodeSessionStatus(fields, value);
            case AgentMessage.CommandResult value -> encodeCommandResult(fields, value);
            case AgentMessage.StartSession value -> encodeStartSession(fields, value);
            case AgentMessage.Input value -> encodeInput(fields, value);
            case AgentMessage.Resize value -> encodeResize(fields, value);
            case AgentMessage.Signal value -> encodeSignal(fields, value);
            case AgentMessage.Terminate value -> encodeTerminate(fields, value);
            case AgentMessage.SessionEvents value -> encodeSessionEvents(fields, value);
            case AgentMessage.SessionAck value -> encodeSessionAck(fields, value);
            case AgentMessage.ResumeSession value -> encodeResumeSession(fields, value);
            case AgentMessage.SessionGap value -> encodeSessionGap(fields, value);
            case AgentMessage.Unknown ignored -> throw new AssertionError("Unknown message handled above");
        }
        return fields.toByteArray();
    }

    private void encodeHello(FieldWriter fields, AgentMessage.Hello value) throws AgentProtocolException {
        requireCurrentVersion(value.protocolVersion().value());
        fields.u16(1, value.protocolVersion().value());
        fields.string(2, value.agentId().value());
        fields.uuid(3, value.instanceId().value());
        fields.string(4, value.agentVersion());
        fields.nested(5, encodeMachineInfo(value.machineInfo()));
        fields.stringMap(6, value.capabilities());
    }

    private void encodeWelcome(FieldWriter fields, AgentMessage.Welcome value) throws AgentProtocolException {
        requireCurrentVersion(value.protocolVersion().value());
        fields.u16(1, value.protocolVersion().value());
        fields.string(2, value.connectionId().value());
        fields.stringMap(3, value.configuration());
    }

    private void encodeHeartbeat(
            FieldWriter fields,
            AgentMessage.Heartbeat value
    ) throws AgentProtocolException {
        fields.string(1, value.agentId().value());
        fields.uuid(2, value.instanceId().value());
        fields.u64(3, value.sentAtEpochMillis());
    }

    private void encodeAgentStatus(FieldWriter fields, AgentMessage.AgentStatus value)
            throws AgentProtocolException {
        fields.string(1, value.agentId().value());
        fields.uuid(2, value.instanceId().value());
        fields.string(3, value.agentVersion());
        fields.nested(4, encodeMachineInfo(value.machineInfo()));
        fields.u32(5, value.activeSessions());
        fields.stringMap(6, value.metrics());
        fields.stringMap(7, value.capabilities());
    }

    private void encodeSessionStatus(FieldWriter fields, AgentMessage.SessionStatus value)
            throws AgentProtocolException {
        fields.string(1, value.sessionId().value());
        fields.u16(2, value.state().wireCode());
        fields.string(3, value.detail());
    }

    private void encodeCommandResult(FieldWriter fields, AgentMessage.CommandResult value)
            throws AgentProtocolException {
        fields.string(1, value.commandId().value());
        if (value.sessionId().isPresent()) {
            fields.string(2, value.sessionId().orElseThrow().value());
        }
        fields.u16(3, value.outcome().wireCode());
        fields.string(4, value.detail());
    }

    private void encodeStartSession(FieldWriter fields, AgentMessage.StartSession value)
            throws AgentProtocolException {
        fields.string(1, value.commandId().value());
        fields.string(2, value.sessionId().value());
        if (value.workspaceId().isPresent()) {
            fields.string(3, value.workspaceId().orElseThrow().value());
        }
        fields.strings(4, value.command());
        fields.string(5, value.workingDirectory());
        fields.stringMap(6, value.environment());
        fields.u16(7, value.columns());
        fields.u16(8, value.rows());
        fields.string(9, value.sandboxPolicy());
        fields.string(10, value.runtime());
    }

    private void encodeInput(FieldWriter fields, AgentMessage.Input value) throws AgentProtocolException {
        fields.string(1, value.commandId().value());
        fields.string(2, value.sessionId().value());
        fields.uuid(3, value.inputId());
        fields.binary(4, value.bytes());
    }

    private void encodeResize(FieldWriter fields, AgentMessage.Resize value) throws AgentProtocolException {
        fields.string(1, value.commandId().value());
        fields.string(2, value.sessionId().value());
        fields.u16(3, value.columns());
        fields.u16(4, value.rows());
    }

    private void encodeSignal(FieldWriter fields, AgentMessage.Signal value) throws AgentProtocolException {
        fields.string(1, value.commandId().value());
        fields.string(2, value.sessionId().value());
        fields.u16(3, value.signal().wireCode());
        fields.s32(4, value.platformCode());
    }

    private void encodeTerminate(
            FieldWriter fields,
            AgentMessage.Terminate value
    ) throws AgentProtocolException {
        fields.string(1, value.commandId().value());
        fields.string(2, value.sessionId().value());
        fields.u16(3, value.mode().wireCode());
        fields.u32(4, value.graceMillis());
    }

    private void encodeSessionEvents(FieldWriter fields, AgentMessage.SessionEvents value)
            throws AgentProtocolException {
        fields.string(1, value.sessionId().value());
        checkCollectionSize(value.events().size(), "events");
        for (SessionEventEnvelope event : value.events()) {
            FieldWriter nested = new FieldWriter();
            nested.u64(1, event.sourceTimestamp().value());
            nested.u16(2, event.eventType());
            nested.u16(3, event.payloadSchemaVersion());
            nested.u32(4, event.flags());
            nested.binary(5, event.payload());
            fields.nested(2, nested.toByteArray());
        }
    }

    private void encodeSessionAck(FieldWriter fields, AgentMessage.SessionAck value)
            throws AgentProtocolException {
        fields.string(1, value.sessionId().value());
        fields.u64(2, value.through().timestamp());
    }

    private void encodeResumeSession(FieldWriter fields, AgentMessage.ResumeSession value)
            throws AgentProtocolException {
        fields.string(1, value.sessionId().value());
        fields.u64(2, value.after().timestamp());
    }

    private void encodeSessionGap(FieldWriter fields, AgentMessage.SessionGap value)
            throws AgentProtocolException {
        fields.string(1, value.sessionId().value());
        fields.u64(2, value.requested().timestamp());
        fields.u64(3, value.availableFrom().value());
    }

    private AgentMessage decodeKnown(AgentMessageType type, FieldReader fields) throws AgentProtocolException {
        return switch (type) {
            case HELLO -> decodeHello(fields);
            case WELCOME -> decodeWelcome(fields);
            case HEARTBEAT -> new AgentMessage.Heartbeat(
                    new AgentId(fields.string(1)),
                    new InstanceId(fields.uuid(2)),
                    fields.u64(3));
            case AGENT_STATUS -> new AgentMessage.AgentStatus(
                    new AgentId(fields.string(1)),
                    new InstanceId(fields.uuid(2)),
                    fields.string(3),
                    decodeMachineInfo(fields.one(4)),
                    fields.int32(5),
                    fields.stringMap(6),
                    fields.stringMap(7));
            case SESSION_STATUS -> new AgentMessage.SessionStatus(
                    new SessionId(fields.string(1)),
                    requiredEnum(AgentMessage.SessionState.fromWireCode(fields.u16(2)), "session state"),
                    fields.string(3));
            case COMMAND_RESULT -> new AgentMessage.CommandResult(
                    new CommandId(fields.string(1)),
                    fields.optionalString(2).map(SessionId::new),
                    requiredEnum(AgentMessage.CommandOutcome.fromWireCode(fields.u16(3)), "command outcome"),
                    fields.string(4));
            case START_SESSION -> decodeStartSession(fields);
            case INPUT -> new AgentMessage.Input(
                    new CommandId(fields.string(1)),
                    new SessionId(fields.string(2)),
                    fields.uuid(3),
                    fields.binary(4));
            case RESIZE -> new AgentMessage.Resize(
                    new CommandId(fields.string(1)),
                    new SessionId(fields.string(2)),
                    fields.u16(3),
                    fields.u16(4));
            case SIGNAL -> new AgentMessage.Signal(
                    new CommandId(fields.string(1)),
                    new SessionId(fields.string(2)),
                    requiredEnum(AgentMessage.SignalKind.fromWireCode(fields.u16(3)), "signal kind"),
                    fields.s32(4));
            case TERMINATE -> new AgentMessage.Terminate(
                    new CommandId(fields.string(1)),
                    new SessionId(fields.string(2)),
                    requiredEnum(AgentMessage.TerminationMode.fromWireCode(fields.u16(3)), "termination mode"),
                    fields.u32(4));
            case SESSION_EVENTS -> decodeSessionEvents(fields);
            case SESSION_ACK -> new AgentMessage.SessionAck(
                    new SessionId(fields.string(1)), new JournalCursor(fields.u64(2)));
            case RESUME_SESSION -> new AgentMessage.ResumeSession(
                    new SessionId(fields.string(1)), new JournalCursor(fields.u64(2)));
            case SESSION_GAP -> new AgentMessage.SessionGap(
                    new SessionId(fields.string(1)),
                    new JournalCursor(fields.u64(2)),
                    new SessionTimestamp(fields.u64(3)));
        };
    }

    private AgentMessage.Hello decodeHello(FieldReader fields) throws AgentProtocolException {
        AgentProtocolVersion version = new AgentProtocolVersion(fields.u16(1));
        requireCurrentVersion(version.value());
        return new AgentMessage.Hello(
                version,
                new AgentId(fields.string(2)),
                new InstanceId(fields.uuid(3)),
                fields.string(4),
                decodeMachineInfo(fields.one(5)),
                fields.stringMap(6));
    }

    private AgentMessage.Welcome decodeWelcome(FieldReader fields) throws AgentProtocolException {
        AgentProtocolVersion version = new AgentProtocolVersion(fields.u16(1));
        requireCurrentVersion(version.value());
        return new AgentMessage.Welcome(version, new ConnectionId(fields.string(2)), fields.stringMap(3));
    }

    private AgentMessage.StartSession decodeStartSession(FieldReader fields) throws AgentProtocolException {
        return new AgentMessage.StartSession(
                new CommandId(fields.string(1)),
                new SessionId(fields.string(2)),
                fields.optionalString(3).map(WorkspaceId::new),
                fields.strings(4),
                fields.string(5),
                fields.stringMap(6),
                fields.u16(7),
                fields.u16(8),
                fields.string(9),
                fields.string(10));
    }

    private AgentMessage.SessionEvents decodeSessionEvents(FieldReader fields) throws AgentProtocolException {
        List<byte[]> encodedEvents = fields.repeated(2);
        checkCollectionSize(encodedEvents.size(), "events");
        List<SessionEventEnvelope> events = new ArrayList<>(encodedEvents.size());
        for (byte[] encodedEvent : encodedEvents) {
            FieldReader event = new FieldReader(encodedEvent);
            events.add(new SessionEventEnvelope(
                    new SessionTimestamp(event.u64(1)),
                    event.u16(2),
                    event.u16(3),
                    event.u32(4),
                    event.binary(5)));
        }
        return new AgentMessage.SessionEvents(new SessionId(fields.string(1)), events);
    }

    private byte[] encodeMachineInfo(MachineInfo machineInfo) throws AgentProtocolException {
        FieldWriter fields = new FieldWriter();
        fields.string(1, machineInfo.hostname());
        fields.string(2, machineInfo.os());
        fields.string(3, machineInfo.architecture());
        return fields.toByteArray();
    }

    private MachineInfo decodeMachineInfo(byte[] value) throws AgentProtocolException {
        FieldReader fields = new FieldReader(value);
        return new MachineInfo(fields.string(1), fields.string(2), fields.string(3));
    }

    private byte[] checkedBinary(ProtocolBytes value) throws AgentProtocolException {
        if (value.size() > limits.maxBinaryBytes()) {
            throw new AgentProtocolException(LIMIT_EXCEEDED, "Binary field exceeds configured limit");
        }
        return value.toByteArray();
    }

    private void checkCollectionSize(int size, String name) throws AgentProtocolException {
        if (size > limits.maxCollectionEntries()) {
            throw new AgentProtocolException(LIMIT_EXCEEDED, name + " exceeds configured entry limit");
        }
    }

    private static void requireCurrentVersion(int value) throws AgentProtocolException {
        if (value != AgentProtocolVersion.CURRENT.value()) {
            throw new AgentProtocolException(
                    UNSUPPORTED_VERSION,
                    "Unsupported Agent protocol version: " + value);
        }
    }

    private static <T> T requiredEnum(T value, String name) throws AgentProtocolException {
        if (value == null) {
            throw new AgentProtocolException(INVALID_FIELD, "Unknown " + name);
        }
        return value;
    }

    private final class FieldWriter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream output = new DataOutputStream(bytes);
        private int fieldCount;

        void u16(int tag, int value) throws AgentProtocolException {
            ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES);
            buffer.putShort((short) ProtocolValidation.unsignedShort(value, "field"));
            field(tag, buffer.array());
        }

        void u32(int tag, long value) throws AgentProtocolException {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            buffer.putInt((int) ProtocolValidation.unsignedInt(value, "field"));
            field(tag, buffer.array());
        }

        void s32(int tag, int value) throws AgentProtocolException {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
            buffer.putInt(value);
            field(tag, buffer.array());
        }

        void u64(int tag, long value) throws AgentProtocolException {
            if (value < 0) {
                throw new AgentProtocolException(INVALID_FIELD, "Unsigned 64-bit field exceeds Java range");
            }
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
            buffer.putLong(value);
            field(tag, buffer.array());
        }

        void uuid(int tag, UUID value) throws AgentProtocolException {
            ByteBuffer buffer = ByteBuffer.allocate(2 * Long.BYTES);
            buffer.putLong(value.getMostSignificantBits());
            buffer.putLong(value.getLeastSignificantBits());
            field(tag, buffer.array());
        }

        void string(int tag, String value) throws AgentProtocolException {
            field(tag, encodeUtf8(value));
        }

        void strings(int tag, List<String> values) throws AgentProtocolException {
            checkCollectionSize(values.size(), "string list");
            for (String value : values) {
                string(tag, value);
            }
        }

        void stringMap(int tag, Map<String, String> values) throws AgentProtocolException {
            checkCollectionSize(values.size(), "string map");
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                FieldWriter mapEntry = new FieldWriter();
                mapEntry.string(1, entry.getKey());
                mapEntry.string(2, entry.getValue());
                nested(tag, mapEntry.toByteArray());
            }
        }

        void binary(int tag, ProtocolBytes value) throws AgentProtocolException {
            field(tag, checkedBinary(value));
        }

        void nested(int tag, byte[] value) throws AgentProtocolException {
            field(tag, value);
        }

        void field(int tag, byte[] value) throws AgentProtocolException {
            if (tag < 1 || tag > 0xffff) {
                throw new AgentProtocolException(INVALID_FIELD, "Field tag must be between 1 and 65535");
            }
            fieldCount++;
            if (fieldCount > limits.maxFieldCount()) {
                throw new AgentProtocolException(LIMIT_EXCEEDED, "Message exceeds configured field count");
            }
            int nextSize = bytes.size() + FIELD_HEADER_BYTES + value.length;
            if (nextSize > limits.maxFrameBytes() - FRAME_HEADER_BYTES) {
                throw new AgentProtocolException(LIMIT_EXCEEDED, "Message payload exceeds configured limit");
            }
            try {
                output.writeShort(tag);
                output.writeInt(value.length);
                output.write(value);
            } catch (IOException impossible) {
                throw new AssertionError("Byte array output failed", impossible);
            }
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }

        private byte[] encodeUtf8(String value) throws AgentProtocolException {
            Objects.requireNonNull(value, "value");
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(value));
                if (encoded.remaining() > limits.maxStringBytes()) {
                    throw new AgentProtocolException(LIMIT_EXCEEDED, "String field exceeds configured limit");
                }
                byte[] result = new byte[encoded.remaining()];
                encoded.get(result);
                return result;
            } catch (CharacterCodingException e) {
                throw new AgentProtocolException(INVALID_FIELD, "String field is not valid Unicode", e);
            }
        }
    }

    private final class FieldReader {
        private final Map<Integer, List<byte[]>> fields = new LinkedHashMap<>();

        FieldReader(byte[] payload) throws AgentProtocolException {
            ByteBuffer input = ByteBuffer.wrap(payload);
            int fieldCount = 0;
            while (input.hasRemaining()) {
                if (input.remaining() < FIELD_HEADER_BYTES) {
                    throw new AgentProtocolException(
                            MALFORMED_FRAME,
                            "Agent protocol field header is incomplete");
                }
                int tag = Short.toUnsignedInt(input.getShort());
                if (tag == 0) {
                    throw new AgentProtocolException(INVALID_FIELD, "Agent protocol field tag zero is invalid");
                }
                long length = Integer.toUnsignedLong(input.getInt());
                if (length > input.remaining()) {
                    throw new AgentProtocolException(
                            MALFORMED_FRAME,
                            "Agent protocol field length exceeds payload");
                }
                fieldCount++;
                if (fieldCount > limits.maxFieldCount()) {
                    throw new AgentProtocolException(LIMIT_EXCEEDED, "Message exceeds configured field count");
                }
                byte[] value = new byte[(int) length];
                input.get(value);
                fields.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(value);
            }
        }

        byte[] one(int tag) throws AgentProtocolException {
            List<byte[]> values = fields.get(tag);
            if (values == null || values.isEmpty()) {
                throw new AgentProtocolException(
                        MISSING_FIELD,
                        "Missing required Agent protocol field: " + tag);
            }
            if (values.size() != 1) {
                throw new AgentProtocolException(DUPLICATE_FIELD, "Duplicate Agent protocol field: " + tag);
            }
            return values.getFirst();
        }

        List<byte[]> repeated(int tag) {
            List<byte[]> values = fields.get(tag);
            return values == null ? List.of() : List.copyOf(values);
        }

        String string(int tag) throws AgentProtocolException {
            return decodeUtf8(one(tag));
        }

        Optional<String> optionalString(int tag) throws AgentProtocolException {
            List<byte[]> values = fields.get(tag);
            if (values == null) {
                return Optional.empty();
            }
            if (values.size() != 1) {
                throw new AgentProtocolException(DUPLICATE_FIELD, "Duplicate Agent protocol field: " + tag);
            }
            return Optional.of(decodeUtf8(values.getFirst()));
        }

        List<String> strings(int tag) throws AgentProtocolException {
            List<byte[]> values = repeated(tag);
            checkCollectionSize(values.size(), "string list");
            List<String> result = new ArrayList<>(values.size());
            for (byte[] value : values) {
                result.add(decodeUtf8(value));
            }
            return List.copyOf(result);
        }

        Map<String, String> stringMap(int tag) throws AgentProtocolException {
            List<byte[]> entries = repeated(tag);
            checkCollectionSize(entries.size(), "string map");
            Map<String, String> result = new LinkedHashMap<>();
            for (byte[] entry : entries) {
                FieldReader fields = new FieldReader(entry);
                String key = fields.string(1);
                String previous = result.put(key, fields.string(2));
                if (previous != null) {
                    throw new AgentProtocolException(DUPLICATE_FIELD, "Duplicate string map key: " + key);
                }
            }
            return Map.copyOf(result);
        }

        int u16(int tag) throws AgentProtocolException {
            byte[] value = exactLength(one(tag), Short.BYTES, tag);
            return Short.toUnsignedInt(ByteBuffer.wrap(value).getShort());
        }

        long u32(int tag) throws AgentProtocolException {
            byte[] value = exactLength(one(tag), Integer.BYTES, tag);
            return Integer.toUnsignedLong(ByteBuffer.wrap(value).getInt());
        }

        int int32(int tag) throws AgentProtocolException {
            long value = u32(tag);
            if (value > Integer.MAX_VALUE) {
                throw new AgentProtocolException(
                        INVALID_FIELD,
                        "Field does not fit a signed Java integer: " + tag);
            }
            return (int) value;
        }

        int s32(int tag) throws AgentProtocolException {
            byte[] value = exactLength(one(tag), Integer.BYTES, tag);
            return ByteBuffer.wrap(value).getInt();
        }

        long u64(int tag) throws AgentProtocolException {
            byte[] value = exactLength(one(tag), Long.BYTES, tag);
            long decoded = ByteBuffer.wrap(value).getLong();
            if (decoded < 0) {
                throw new AgentProtocolException(
                        INVALID_FIELD,
                        "Unsigned 64-bit field exceeds Java range: " + tag);
            }
            return decoded;
        }

        UUID uuid(int tag) throws AgentProtocolException {
            byte[] value = exactLength(one(tag), 2 * Long.BYTES, tag);
            ByteBuffer buffer = ByteBuffer.wrap(value);
            return new UUID(buffer.getLong(), buffer.getLong());
        }

        ProtocolBytes binary(int tag) throws AgentProtocolException {
            byte[] value = one(tag);
            if (value.length > limits.maxBinaryBytes()) {
                throw new AgentProtocolException(LIMIT_EXCEEDED, "Binary field exceeds configured limit");
            }
            return ProtocolBytes.copyOf(value);
        }

        private byte[] exactLength(byte[] value, int expected, int tag) throws AgentProtocolException {
            if (value.length != expected) {
                throw new AgentProtocolException(INVALID_FIELD,
                        "Agent protocol field " + tag + " must contain " + expected + " bytes");
            }
            return value;
        }

        private String decodeUtf8(byte[] value) throws AgentProtocolException {
            if (value.length > limits.maxStringBytes()) {
                throw new AgentProtocolException(LIMIT_EXCEEDED, "String field exceeds configured limit");
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new AgentProtocolException(INVALID_FIELD, "String field is not valid UTF-8", e);
            }
        }
    }
}
