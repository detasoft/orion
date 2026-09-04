package pro.deta.orion.agent.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class CborWriter {
    private final AgentProtocolLimits limits;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    CborWriter(AgentProtocolLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    void array(int size) throws AgentProtocolException {
        collection(4, size);
    }

    void map(int size) throws AgentProtocolException {
        collection(5, size);
    }

    void unsigned(int value) {
        typeAndValue(0, value);
    }

    void unsigned(EventId value) {
        typeAndValue(0, value.value());
    }

    void signed(long value) {
        if (value >= 0) {
            typeAndValue(0, value);
        } else {
            typeAndValue(1, -(value + 1));
        }
    }

    void bool(boolean value) {
        output.write(value ? 0xf5 : 0xf4);
    }

    void nullValue() {
        output.write(0xf6);
    }

    void text(String value) throws AgentProtocolException {
        byte[] encoded = ProtocolValidation.utf8(Objects.requireNonNull(value, "value"));
        if (encoded.length > limits.maxStringBytes()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "String exceeds configured limit");
        }
        typeAndValue(3, encoded.length);
        output.writeBytes(encoded);
    }

    void bytes(ProtocolBytes value) throws AgentProtocolException {
        bytes(value.toByteArray());
    }

    void bytes(byte[] value) throws AgentProtocolException {
        Objects.requireNonNull(value, "value");
        if (value.length > limits.maxBinaryBytes()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "Binary value exceeds configured limit");
        }
        typeAndValue(2, value.length);
        output.writeBytes(value);
    }

    void uuid(UUID value) throws AgentProtocolException {
        byte[] bytes = new byte[16];
        putLong(bytes, 0, value.getMostSignificantBits());
        putLong(bytes, 8, value.getLeastSignificantBits());
        bytes(bytes);
    }

    void stringMap(Map<String, String> values) throws AgentProtocolException {
        if (values.size() > limits.maxCollectionEntries()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "Map exceeds configured entry limit");
        }
        map(values.size());
        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, String> entry : entries) {
            text(entry.getKey());
            text(entry.getValue());
        }
    }

    void raw(ProtocolBytes value) throws AgentProtocolException {
        byte[] encoded = value.toByteArray();
        int itemLength = CborItemScanner.itemLengthWithStringLimits(encoded, 0, encoded.length, limits);
        if (itemLength < 0 || itemLength != encoded.length) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.MALFORMED_CBOR,
                    "Raw value must contain exactly one complete CBOR item");
        }
        output.writeBytes(encoded);
    }

    byte[] toByteArray() throws AgentProtocolException {
        byte[] result = output.toByteArray();
        if (result.length > limits.maxMessageBytes()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "Encoded CBOR item exceeds configured limit");
        }
        int itemLength = CborItemScanner.itemLengthWithStringLimits(
                result,
                0,
                result.length,
                limits);
        if (itemLength < 0 || itemLength != result.length) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.MALFORMED_CBOR,
                    "Encoded CBOR must contain exactly one complete item");
        }
        return result;
    }

    private void collection(int majorType, int size) throws AgentProtocolException {
        if (size < 0 || size > limits.maxCollectionEntries()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "Collection exceeds configured entry limit");
        }
        typeAndValue(majorType, size);
    }

    private void typeAndValue(int majorType, long value) {
        if (Long.compareUnsigned(value, 24) < 0) {
            output.write((majorType << 5) | (int) value);
        } else if (Long.compareUnsigned(value, 0xff) <= 0) {
            output.write((majorType << 5) | 24);
            output.write((int) value);
        } else if (Long.compareUnsigned(value, 0xffff) <= 0) {
            output.write((majorType << 5) | 25);
            putUnsigned(value, 2);
        } else if (Long.compareUnsigned(value, 0xffff_ffffL) <= 0) {
            output.write((majorType << 5) | 26);
            putUnsigned(value, 4);
        } else {
            output.write((majorType << 5) | 27);
            putUnsigned(value, 8);
        }
    }

    private void putUnsigned(long value, int size) {
        for (int shift = (size - 1) * Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            output.write((int) (value >>> shift));
        }
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> ((Long.BYTES - index - 1) * Byte.SIZE));
        }
    }
}
