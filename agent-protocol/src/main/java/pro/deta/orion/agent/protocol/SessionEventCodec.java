package pro.deta.orion.agent.protocol;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.INVALID_FIELD;
import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.LIMIT_EXCEEDED;
import static pro.deta.orion.agent.protocol.AgentProtocolException.Reason.MALFORMED_CBOR;

public final class SessionEventCodec {
    private static final BigInteger MAX_UNSIGNED_SHORT = BigInteger.valueOf(0xffff);
    private static final BigInteger MIN_SIGNED_INT = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger MAX_SIGNED_INT = BigInteger.valueOf(Integer.MAX_VALUE);
    private final AgentProtocolLimits limits;

    public SessionEventCodec(AgentProtocolLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public byte[] encode(EventId eventId, SessionEventPayload payload) throws AgentProtocolException {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(payload, "payload");
        CborWriter writer = new CborWriter(limits);
        writer.array(3);
        writer.unsigned(eventId);
        switch (payload) {
            case SessionEventPayload.PtyOutput value -> {
                writer.unsigned(SessionEventType.PTY_OUTPUT);
                writer.bytes(value.bytes());
            }
            case SessionEventPayload.PtyInput value -> {
                writer.unsigned(SessionEventType.PTY_INPUT);
                writer.array(2);
                writer.text(value.commandId().value());
                writer.bytes(value.bytes());
            }
            case SessionEventPayload.PtyResize value -> {
                writer.unsigned(SessionEventType.PTY_RESIZE);
                writer.array(2);
                writer.unsigned(value.columns());
                writer.unsigned(value.rows());
            }
            case SessionEventPayload.ProcessExited value -> {
                writer.unsigned(SessionEventType.PROCESS_EXITED);
                writer.array(1);
                writer.signed(value.exitCode());
            }
        }
        return writer.toByteArray();
    }

    public byte[] encodeOpaque(
            EventId eventId,
            int eventType,
            ProtocolBytes encodedPayload,
            List<ProtocolBytes> trailingFields
    ) throws AgentProtocolException {
        ProtocolValidation.unsignedShort(eventType, "eventType");
        Objects.requireNonNull(encodedPayload, "encodedPayload");
        trailingFields = List.copyOf(trailingFields);
        if (trailingFields.size() > limits.maxCollectionEntries() - 3) {
            throw new AgentProtocolException(LIMIT_EXCEEDED, "Event trailing fields exceed configured limit");
        }
        CborWriter writer = new CborWriter(limits);
        writer.array(3 + trailingFields.size());
        writer.unsigned(eventId);
        writer.unsigned(eventType);
        writer.raw(encodedPayload);
        for (ProtocolBytes field : trailingFields) {
            writer.raw(Objects.requireNonNull(field, "trailing field"));
        }
        return writer.toByteArray();
    }

    public SessionEventRecord decode(byte[] encoded) throws AgentProtocolException {
        Objects.requireNonNull(encoded, "encoded");
        return decode(encoded, 0, encoded.length);
    }

    SessionEventRecord decode(byte[] encoded, int from, int to) throws AgentProtocolException {
        Objects.requireNonNull(encoded, "encoded");
        Objects.checkFromToIndex(from, to, encoded.length);
        if (to - from > limits.maxMessageBytes()) {
            throw new AgentProtocolException(LIMIT_EXCEEDED, "Session event exceeds configured limit");
        }
        int itemLength = CborItemScanner.itemLength(encoded, from, to, limits);
        if (itemLength < 0 || itemLength != to - from) {
            throw new AgentProtocolException(MALFORMED_CBOR, "Session event must be one complete CBOR item");
        }
        List<CborArrayItems.Slice> items = CborArrayItems.parse(encoded, from, to, limits);
        if (items.size() < 3) {
            throw new AgentProtocolException(INVALID_FIELD, "Session event must contain at least three fields");
        }
        EventId eventId = eventId(read(items.get(0), encoded), "eventId");
        int eventType = unsignedShort(read(items.get(1), encoded), "eventType");
        CborArrayItems.Slice payload = items.get(2);
        return new SessionEventRecord(
                eventId,
                eventType,
                ProtocolBytes.copyOf(encoded, payload.from(), payload.to()),
                ProtocolBytes.copyOf(encoded, from, to),
                items.size() - 3);
    }

    public Optional<SessionEventPayload> decodeKnownPayload(SessionEventRecord event)
            throws AgentProtocolException {
        Objects.requireNonNull(event, "event");
        return switch (event.eventType()) {
            case SessionEventType.PTY_OUTPUT -> Optional.of(
                    new SessionEventPayload.PtyOutput(
                            ProtocolBytes.copyOf(bytes(payload(event), "PTY_OUTPUT"))));
            case SessionEventType.PTY_INPUT -> Optional.of(decodePtyInput(payloadArray(event, "PTY_INPUT")));
            case SessionEventType.PTY_RESIZE -> Optional.of(decodePtyResize(payloadArray(event, "PTY_RESIZE")));
            case SessionEventType.PROCESS_EXITED -> Optional.of(
                    decodeProcessExited(payloadArray(event, "PROCESS_EXITED")));
            default -> Optional.empty();
        };
    }

    private SessionEventPayload.PtyInput decodePtyInput(List<CborReader.Value> fields)
            throws AgentProtocolException {
        requireFields(fields, 2, "PTY_INPUT");
        return new SessionEventPayload.PtyInput(
                new CommandId(text(fields.get(0), "PTY_INPUT commandId")),
                ProtocolBytes.copyOf(bytes(fields.get(1), "PTY_INPUT bytes")));
    }

    private SessionEventPayload.PtyResize decodePtyResize(List<CborReader.Value> fields)
            throws AgentProtocolException {
        requireFields(fields, 2, "PTY_RESIZE");
        return new SessionEventPayload.PtyResize(
                unsignedShort(fields.get(0), "PTY_RESIZE columns"),
                unsignedShort(fields.get(1), "PTY_RESIZE rows"));
    }

    private SessionEventPayload.ProcessExited decodeProcessExited(List<CborReader.Value> fields)
            throws AgentProtocolException {
        requireFields(fields, 1, "PROCESS_EXITED");
        return new SessionEventPayload.ProcessExited(signedInt(fields.get(0), "PROCESS_EXITED exitCode"));
    }

    private CborReader.Value payload(SessionEventRecord event) throws AgentProtocolException {
        return new CborReader(event.encodedPayload().toByteArray(), limits).readRoot();
    }

    private List<CborReader.Value> payloadArray(SessionEventRecord event, String name)
            throws AgentProtocolException {
        CborReader.Value payload = payload(event);
        if (!(payload instanceof CborReader.ArrayValue array)) {
            throw new AgentProtocolException(INVALID_FIELD, name + " payload must be a CBOR array");
        }
        return array.values();
    }

    private CborReader.Value read(CborArrayItems.Slice slice, byte[] encoded) throws AgentProtocolException {
        return new CborReader(encoded, slice.from(), slice.to(), limits).readRoot();
    }

    private static EventId eventId(CborReader.Value value, String name) throws AgentProtocolException {
        if (!(value instanceof CborReader.IntegerValue integer)) {
            throw new AgentProtocolException(INVALID_FIELD, name + " must be an unsigned integer");
        }
        try {
            return EventId.fromUnsigned(integer.value());
        } catch (IllegalArgumentException e) {
            throw new AgentProtocolException(INVALID_FIELD, e.getMessage(), e);
        }
    }

    private static int unsignedShort(CborReader.Value value, String name) throws AgentProtocolException {
        if (!(value instanceof CborReader.IntegerValue integer)
                || integer.value().signum() < 0
                || integer.value().compareTo(MAX_UNSIGNED_SHORT) > 0) {
            throw new AgentProtocolException(INVALID_FIELD, name + " must fit an unsigned 16-bit integer");
        }
        return integer.value().intValue();
    }

    private static int signedInt(CborReader.Value value, String name) throws AgentProtocolException {
        if (!(value instanceof CborReader.IntegerValue integer)
                || integer.value().compareTo(MIN_SIGNED_INT) < 0
                || integer.value().compareTo(MAX_SIGNED_INT) > 0) {
            throw new AgentProtocolException(INVALID_FIELD, name + " must fit a signed 32-bit integer");
        }
        return integer.value().intValue();
    }

    private static String text(CborReader.Value value, String name) throws AgentProtocolException {
        if (!(value instanceof CborReader.TextValue text)) {
            throw new AgentProtocolException(INVALID_FIELD, name + " must be a CBOR text string");
        }
        return text.value();
    }

    private static byte[] bytes(CborReader.Value value, String name) throws AgentProtocolException {
        if (!(value instanceof CborReader.BytesValue binary)) {
            throw new AgentProtocolException(INVALID_FIELD, name + " must be a CBOR byte string");
        }
        return java.util.Arrays.copyOf(binary.value(), binary.value().length);
    }

    private static void requireFields(List<?> fields, int minimum, String name) throws AgentProtocolException {
        if (fields.size() < minimum) {
            throw new AgentProtocolException(INVALID_FIELD, name + " payload has missing fields");
        }
    }
}
