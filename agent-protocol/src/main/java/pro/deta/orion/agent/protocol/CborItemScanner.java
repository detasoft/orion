package pro.deta.orion.agent.protocol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

final class CborItemScanner {
    private static final int INCOMPLETE = -1;

    private final AgentProtocolLimits limits;
    private final boolean enforceStringLimits;
    private final Deque<Frame> containers = new ArrayDeque<>();
    private int itemStart;
    private int position;

    CborItemScanner(AgentProtocolLimits limits) {
        this(limits, false);
    }

    private CborItemScanner(AgentProtocolLimits limits, boolean enforceStringLimits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.enforceStringLimits = enforceStringLimits;
    }

    static int itemLength(byte[] bytes, int offset, AgentProtocolLimits limits) throws AgentProtocolException {
        return itemLength(bytes, offset, bytes.length, limits);
    }

    static int itemLength(byte[] bytes, int offset, int end, AgentProtocolLimits limits)
            throws AgentProtocolException {
        return itemLength(bytes, offset, end, limits, false);
    }

    static int itemLengthWithStringLimits(byte[] bytes, int offset, int end, AgentProtocolLimits limits)
            throws AgentProtocolException {
        return itemLength(bytes, offset, end, limits, true);
    }

    private static int itemLength(
            byte[] bytes,
            int offset,
            int end,
            AgentProtocolLimits limits,
            boolean enforceStringLimits
    ) throws AgentProtocolException {
        CborItemScanner scanner = new CborItemScanner(limits, enforceStringLimits);
        scanner.reset(offset);
        int itemEnd = scanner.scan(bytes, end);
        return itemEnd == INCOMPLETE ? INCOMPLETE : itemEnd - offset;
    }

    int scan(byte[] bytes, int end) throws AgentProtocolException {
        while (position < end) {
            Frame container = containers.peek();
            if (container != null && container.kind == Kind.INDEFINITE_STRING) {
                int complete = scanStringChunk(bytes, end, container);
                if (complete != INCOMPLETE) {
                    return complete;
                }
                continue;
            }
            if ((bytes[position] & 0xff) == 0xff) {
                int complete = closeIndefiniteContainer();
                if (complete != INCOMPLETE) {
                    return complete;
                }
                continue;
            }
            if (containers.size() > limits.maxNestingDepth()) {
                throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, "CBOR nesting limit exceeded");
            }
            int tokenStart = position;
            Header header = header(bytes, tokenStart, end);
            if (header == null) {
                return INCOMPLETE;
            }
            position = header.end;
            ValueState state = switch (header.majorType) {
                case 0, 1, 7 -> ValueState.COMPLETE;
                case 2, 3 -> scanString(bytes, end, tokenStart, header);
                case 4 -> openCollection(header, Kind.ARRAY, "CBOR array");
                case 5 -> openCollection(header, Kind.MAP, "CBOR map");
                case 6 -> {
                    containers.push(new Frame(Kind.DEFINITE, 1, 0, -1));
                    yield ValueState.OPENED;
                }
                default -> throw failure(
                        AgentProtocolException.Reason.MALFORMED_CBOR,
                        "Unknown CBOR major type");
            };
            if (state == ValueState.INCOMPLETE) {
                return INCOMPLETE;
            }
            if (state == ValueState.COMPLETE) {
                int complete = completeValue();
                if (complete != INCOMPLETE) {
                    return complete;
                }
            }
        }
        return INCOMPLETE;
    }

    void reset(int offset) {
        containers.clear();
        itemStart = offset;
        position = offset;
    }

    void shift(int count) {
        itemStart -= count;
        position -= count;
    }

    private ValueState scanString(byte[] bytes, int end, int tokenStart, Header header)
            throws AgentProtocolException {
        if (header.additionalInfo == 31) {
            containers.push(new Frame(Kind.INDEFINITE_STRING, 0, 0, header.majorType));
            return ValueState.OPENED;
        }
        int length = length(header.argument, stringLimit(header.majorType), "CBOR string");
        long valueEnd = (long) position + length;
        checkItemLength(valueEnd);
        if (valueEnd > end) {
            position = tokenStart;
            return ValueState.INCOMPLETE;
        }
        position = (int) valueEnd;
        return ValueState.COMPLETE;
    }

    private ValueState openCollection(Header header, Kind kind, String name) throws AgentProtocolException {
        if (header.additionalInfo == 31) {
            containers.push(new Frame(kind.indefinite(), 0, 0, -1));
            return ValueState.OPENED;
        }
        int entries = length(header.argument, limits.maxCollectionEntries(), name);
        long values = kind == Kind.MAP ? (long) entries * 2 : entries;
        if (values == 0) {
            return ValueState.COMPLETE;
        }
        containers.push(new Frame(Kind.DEFINITE, values, 0, -1));
        return ValueState.OPENED;
    }

    private int scanStringChunk(byte[] bytes, int end, Frame string) throws AgentProtocolException {
        if ((bytes[position] & 0xff) == 0xff) {
            containers.pop();
            position++;
            return completeValue();
        }
        Header chunk = header(bytes, position, end);
        if (chunk == null) {
            return INCOMPLETE;
        }
        if (chunk.majorType != string.stringMajorType || chunk.additionalInfo == 31) {
            throw failure(
                    AgentProtocolException.Reason.MALFORMED_CBOR,
                    "Indefinite CBOR string contains an invalid chunk");
        }
        int maximum = stringLimit(chunk.majorType);
        int length = length(chunk.argument, maximum, "CBOR string chunk");
        long encodedBytes = string.encodedBytes + length;
        if (encodedBytes > maximum) {
            throw failure(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "CBOR string exceeds configured limit");
        }
        long chunkEnd = (long) chunk.end + length;
        checkItemLength(chunkEnd);
        if (chunkEnd > end) {
            return INCOMPLETE;
        }
        string.encodedBytes = encodedBytes;
        position = (int) chunkEnd;
        return INCOMPLETE;
    }

    private int stringLimit(int majorType) {
        if (!enforceStringLimits) {
            return limits.maxMessageBytes();
        }
        return majorType == 2 ? limits.maxBinaryBytes() : limits.maxStringBytes();
    }

    private int closeIndefiniteContainer() throws AgentProtocolException {
        Frame frame = containers.peek();
        if (frame == null || (frame.kind != Kind.INDEFINITE_ARRAY && frame.kind != Kind.INDEFINITE_MAP)) {
            throw failure(AgentProtocolException.Reason.MALFORMED_CBOR, "Unexpected CBOR break marker");
        }
        if (frame.kind == Kind.INDEFINITE_MAP && (frame.completedValues & 1) != 0) {
            throw failure(
                    AgentProtocolException.Reason.MALFORMED_CBOR,
                    "Indefinite CBOR map has no value for key");
        }
        containers.pop();
        position++;
        return completeValue();
    }

    private int completeValue() throws AgentProtocolException {
        while (true) {
            Frame parent = containers.peek();
            if (parent == null) {
                return position;
            }
            if (parent.kind == Kind.DEFINITE) {
                parent.remaining--;
                if (parent.remaining == 0) {
                    containers.pop();
                    continue;
                }
                return INCOMPLETE;
            }
            parent.completedValues++;
            int entries = parent.kind == Kind.INDEFINITE_MAP
                    ? parent.completedValues / 2
                    : parent.completedValues;
            if (entries > limits.maxCollectionEntries()) {
                throw failure(
                        AgentProtocolException.Reason.LIMIT_EXCEEDED,
                        "CBOR collection exceeds configured limit");
            }
            return INCOMPLETE;
        }
    }

    private Header header(byte[] bytes, int offset, int end) throws AgentProtocolException {
        int initial = bytes[offset] & 0xff;
        int majorType = initial >>> 5;
        int additionalInfo = initial & 0x1f;
        if (additionalInfo >= 28 && additionalInfo <= 30) {
            throw failure(AgentProtocolException.Reason.MALFORMED_CBOR, "Reserved CBOR additional information");
        }
        if (additionalInfo == 31 && (majorType < 2 || majorType > 5)) {
            throw failure(
                    AgentProtocolException.Reason.MALFORMED_CBOR,
                    "Indefinite length is invalid for this CBOR type");
        }
        if (additionalInfo < 24 || additionalInfo == 31) {
            return new Header(majorType, additionalInfo, additionalInfo, offset + 1);
        }
        int argumentBytes = 1 << (additionalInfo - 24);
        if (end - offset - 1 < argumentBytes) {
            return null;
        }
        long argument = 0;
        for (int index = 0; index < argumentBytes; index++) {
            argument = (argument << 8) | (bytes[offset + 1 + index] & 0xffL);
        }
        return new Header(majorType, additionalInfo, argument, offset + 1 + argumentBytes);
    }

    private void checkItemLength(long itemEnd) throws AgentProtocolException {
        if (itemEnd - itemStart > limits.maxMessageBytes()) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, "CBOR item exceeds configured limit");
        }
    }

    private static int length(long value, int maximum, String name) throws AgentProtocolException {
        if (value < 0 || value > maximum) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, name + " exceeds configured limit");
        }
        return (int) value;
    }

    private static AgentProtocolException failure(AgentProtocolException.Reason reason, String message) {
        return new AgentProtocolException(reason, message);
    }

    private enum Kind {
        DEFINITE,
        ARRAY,
        MAP,
        INDEFINITE_ARRAY,
        INDEFINITE_MAP,
        INDEFINITE_STRING;

        Kind indefinite() {
            return this == ARRAY ? INDEFINITE_ARRAY : INDEFINITE_MAP;
        }
    }

    private enum ValueState {
        COMPLETE,
        OPENED,
        INCOMPLETE
    }

    private static final class Frame {
        private final Kind kind;
        private long remaining;
        private int completedValues;
        private final int stringMajorType;
        private long encodedBytes;

        private Frame(Kind kind, long remaining, int completedValues, int stringMajorType) {
            this.kind = kind;
            this.remaining = remaining;
            this.completedValues = completedValues;
            this.stringMajorType = stringMajorType;
        }
    }

    private record Header(int majorType, int additionalInfo, long argument, int end) {
    }
}
