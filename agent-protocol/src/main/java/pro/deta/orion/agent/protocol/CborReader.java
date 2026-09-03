package pro.deta.orion.agent.protocol;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class CborReader {
    private static final BigInteger NEGATIVE_ONE = BigInteger.valueOf(-1);
    private final byte[] bytes;
    private final AgentProtocolLimits limits;
    private final int end;
    private int position;

    CborReader(byte[] bytes, AgentProtocolLimits limits) {
        this(bytes, 0, bytes.length, limits);
    }

    CborReader(byte[] bytes, int from, int to, AgentProtocolLimits limits) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromToIndex(from, to, bytes.length);
        this.limits = Objects.requireNonNull(limits, "limits");
        position = from;
        end = to;
    }

    Value readRoot() throws AgentProtocolException {
        Value value = readValue(0);
        if (position != end) {
            throw malformed("CBOR item has trailing bytes");
        }
        return value;
    }

    private Value readValue(int depth) throws AgentProtocolException {
        if (depth > limits.maxNestingDepth()) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, "CBOR nesting limit exceeded");
        }
        Header header = readHeader();
        return switch (header.majorType()) {
            case 0 -> new IntegerValue(header.argument());
            case 1 -> new IntegerValue(NEGATIVE_ONE.subtract(header.argument()));
            case 2 -> new BytesValue(readByteString(header));
            case 3 -> new TextValue(readTextString(header));
            case 4 -> readArray(header, depth);
            case 5 -> readMap(header, depth);
            case 6 -> new TaggedValue(header.argument(), readValue(depth + 1));
            case 7 -> readSimple(header);
            default -> throw malformed("Unknown CBOR major type");
        };
    }

    private byte[] readByteString(Header header) throws AgentProtocolException {
        if (!header.indefinite()) {
            return readBytes(length(header.argument(), limits.maxBinaryBytes(), "Binary value"));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (!consumeBreak()) {
            Header chunk = readHeader();
            if (chunk.majorType() != 2 || chunk.indefinite()) {
                throw malformed("Indefinite CBOR byte string contains an invalid chunk");
            }
            byte[] value = readBytes(length(chunk.argument(), limits.maxBinaryBytes(), "Binary chunk"));
            if (output.size() + value.length > limits.maxBinaryBytes()) {
                throw failure(
                        AgentProtocolException.Reason.LIMIT_EXCEEDED,
                        "Binary value exceeds configured limit");
            }
            output.writeBytes(value);
        }
        return output.toByteArray();
    }

    private String readTextString(Header header) throws AgentProtocolException {
        if (!header.indefinite()) {
            return decodeUtf8(readBytes(length(header.argument(), limits.maxStringBytes(), "String")));
        }
        StringBuilder result = new StringBuilder();
        int encodedBytes = 0;
        while (!consumeBreak()) {
            Header chunk = readHeader();
            if (chunk.majorType() != 3 || chunk.indefinite()) {
                throw malformed("Indefinite CBOR text string contains an invalid chunk");
            }
            int length = length(chunk.argument(), limits.maxStringBytes(), "String chunk");
            encodedBytes += length;
            if (encodedBytes > limits.maxStringBytes()) {
                throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, "String exceeds configured limit");
            }
            result.append(decodeUtf8(readBytes(length)));
        }
        return result.toString();
    }

    private ArrayValue readArray(Header header, int depth) throws AgentProtocolException {
        List<Value> values = new ArrayList<>();
        if (header.indefinite()) {
            while (!consumeBreak()) {
                checkEntries(values.size() + 1);
                values.add(readValue(depth + 1));
            }
        } else {
            int count = length(header.argument(), limits.maxCollectionEntries(), "Array");
            for (int index = 0; index < count; index++) {
                values.add(readValue(depth + 1));
            }
        }
        return new ArrayValue(List.copyOf(values));
    }

    private MapValue readMap(Header header, int depth) throws AgentProtocolException {
        List<MapEntry> entries = new ArrayList<>();
        if (header.indefinite()) {
            while (!consumeBreak()) {
                checkEntries(entries.size() + 1);
                entries.add(new MapEntry(readValue(depth + 1), readValue(depth + 1)));
            }
        } else {
            int count = length(header.argument(), limits.maxCollectionEntries(), "Map");
            for (int index = 0; index < count; index++) {
                entries.add(new MapEntry(readValue(depth + 1), readValue(depth + 1)));
            }
        }
        return new MapValue(List.copyOf(entries));
    }

    private Value readSimple(Header header) throws AgentProtocolException {
        if (header.indefinite()) {
            throw malformed("Unexpected CBOR break marker");
        }
        return switch (header.additionalInfo()) {
            case 20 -> new BooleanValue(false);
            case 21 -> new BooleanValue(true);
            case 22 -> NullValue.INSTANCE;
            default -> new SimpleValue(header.additionalInfo(), header.argument());
        };
    }

    private Header readHeader() throws AgentProtocolException {
        if (position >= end) {
            throw malformed("Incomplete CBOR item");
        }
        int initial = bytes[position++] & 0xff;
        int majorType = initial >>> 5;
        int additionalInfo = initial & 0x1f;
        if (additionalInfo >= 28 && additionalInfo <= 30) {
            throw malformed("Reserved CBOR additional information");
        }
        if (additionalInfo < 24) {
            return new Header(majorType, additionalInfo, BigInteger.valueOf(additionalInfo), false);
        }
        if (additionalInfo == 31) {
            if (majorType < 2 || majorType > 5) {
                throw malformed("Indefinite length is invalid for this CBOR type");
            }
            return new Header(majorType, additionalInfo, BigInteger.ZERO, true);
        }
        int argumentBytes = 1 << (additionalInfo - 24);
        byte[] argument = readBytes(argumentBytes);
        return new Header(majorType, additionalInfo, new BigInteger(1, argument), false);
    }

    private boolean consumeBreak() {
        if (position < end && (bytes[position] & 0xff) == 0xff) {
            position++;
            return true;
        }
        return false;
    }

    private byte[] readBytes(int length) throws AgentProtocolException {
        if (end - position < length) {
            throw malformed("Incomplete CBOR item");
        }
        byte[] result = java.util.Arrays.copyOfRange(bytes, position, position + length);
        position += length;
        return result;
    }

    private String decodeUtf8(byte[] value) throws AgentProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.INVALID_FIELD,
                    "String is not valid UTF-8",
                    e);
        }
    }

    private int length(BigInteger value, int maximum, String name) throws AgentProtocolException {
        if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, name + " exceeds configured limit");
        }
        return value.intValue();
    }

    private void checkEntries(int entries) throws AgentProtocolException {
        if (entries > limits.maxCollectionEntries()) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, "Collection exceeds configured limit");
        }
    }

    private static AgentProtocolException malformed(String message) {
        return failure(AgentProtocolException.Reason.MALFORMED_CBOR, message);
    }

    private static AgentProtocolException failure(AgentProtocolException.Reason reason, String message) {
        return new AgentProtocolException(reason, message);
    }

    sealed interface Value permits IntegerValue, BytesValue, TextValue, ArrayValue, MapValue,
            BooleanValue, NullValue, SimpleValue, TaggedValue {
    }

    record IntegerValue(BigInteger value) implements Value {
    }

    record BytesValue(byte[] value) implements Value {
    }

    record TextValue(String value) implements Value {
    }

    record ArrayValue(List<Value> values) implements Value {
    }

    record MapEntry(Value key, Value value) {
    }

    record MapValue(List<MapEntry> entries) implements Value {
    }

    record BooleanValue(boolean value) implements Value {
    }

    enum NullValue implements Value {
        INSTANCE
    }

    record SimpleValue(int additionalInfo, BigInteger argument) implements Value {
    }

    record TaggedValue(BigInteger tag, Value value) implements Value {
    }

    private record Header(int majorType, int additionalInfo, BigInteger argument, boolean indefinite) {
    }
}
