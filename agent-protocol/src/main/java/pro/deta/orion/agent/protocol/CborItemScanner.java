package pro.deta.orion.agent.protocol;

final class CborItemScanner {
    private static final int INCOMPLETE = -1;

    private CborItemScanner() {
    }

    static int itemLength(byte[] bytes, int offset, AgentProtocolLimits limits) throws AgentProtocolException {
        int end = scan(bytes, offset, 0, offset, limits);
        return end == INCOMPLETE ? INCOMPLETE : end - offset;
    }

    private static int scan(
            byte[] bytes,
            int offset,
            int depth,
            int itemStart,
            AgentProtocolLimits limits
    ) throws AgentProtocolException {
        if (depth > limits.maxNestingDepth()) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, "CBOR nesting limit exceeded");
        }
        Header header = header(bytes, offset);
        if (header == null) {
            return INCOMPLETE;
        }
        int position = header.end();
        return switch (header.majorType()) {
            case 0, 1 -> position;
            case 2, 3 -> scanString(bytes, header, itemStart, limits);
            case 4 -> scanArray(bytes, header, depth, itemStart, limits);
            case 5 -> scanMap(bytes, header, depth, itemStart, limits);
            case 6 -> scan(bytes, position, depth + 1, itemStart, limits);
            case 7 -> {
                if (header.additionalInfo() == 31) {
                    throw failure(AgentProtocolException.Reason.MALFORMED_CBOR, "Unexpected CBOR break marker");
                }
                yield position;
            }
            default -> throw failure(AgentProtocolException.Reason.MALFORMED_CBOR, "Unknown CBOR major type");
        };
    }

    private static int scanString(
            byte[] bytes,
            Header header,
            int itemStart,
            AgentProtocolLimits limits
    ) throws AgentProtocolException {
        if (header.additionalInfo() != 31) {
            int length = length(header.argument(), limits.maxMessageBytes(), "CBOR string");
            return advance(bytes, header.end(), length, itemStart, limits);
        }

        int position = header.end();
        while (true) {
            if (position >= bytes.length) {
                return INCOMPLETE;
            }
            if ((bytes[position] & 0xff) == 0xff) {
                return position + 1;
            }
            Header chunk = header(bytes, position);
            if (chunk == null) {
                return INCOMPLETE;
            }
            if (chunk.majorType() != header.majorType() || chunk.additionalInfo() == 31) {
                throw failure(
                        AgentProtocolException.Reason.MALFORMED_CBOR,
                        "Indefinite CBOR string contains an invalid chunk");
            }
            int length = length(chunk.argument(), limits.maxMessageBytes(), "CBOR string chunk");
            position = advance(bytes, chunk.end(), length, itemStart, limits);
            if (position == INCOMPLETE) {
                return INCOMPLETE;
            }
        }
    }

    private static int scanArray(
            byte[] bytes,
            Header header,
            int depth,
            int itemStart,
            AgentProtocolLimits limits
    ) throws AgentProtocolException {
        int position = header.end();
        if (header.additionalInfo() == 31) {
            int entries = 0;
            while (true) {
                if (position >= bytes.length) {
                    return INCOMPLETE;
                }
                if ((bytes[position] & 0xff) == 0xff) {
                    return position + 1;
                }
                checkEntries(++entries, limits);
                position = scan(bytes, position, depth + 1, itemStart, limits);
                if (position == INCOMPLETE) {
                    return INCOMPLETE;
                }
            }
        }

        int entries = length(header.argument(), limits.maxCollectionEntries(), "CBOR array");
        for (int index = 0; index < entries; index++) {
            position = scan(bytes, position, depth + 1, itemStart, limits);
            if (position == INCOMPLETE) {
                return INCOMPLETE;
            }
        }
        return position;
    }

    private static int scanMap(
            byte[] bytes,
            Header header,
            int depth,
            int itemStart,
            AgentProtocolLimits limits
    ) throws AgentProtocolException {
        int position = header.end();
        if (header.additionalInfo() == 31) {
            int entries = 0;
            while (true) {
                if (position >= bytes.length) {
                    return INCOMPLETE;
                }
                if ((bytes[position] & 0xff) == 0xff) {
                    return position + 1;
                }
                checkEntries(++entries, limits);
                position = scan(bytes, position, depth + 1, itemStart, limits);
                if (position == INCOMPLETE) {
                    return INCOMPLETE;
                }
                position = scan(bytes, position, depth + 1, itemStart, limits);
                if (position == INCOMPLETE) {
                    return INCOMPLETE;
                }
            }
        }

        int entries = length(header.argument(), limits.maxCollectionEntries(), "CBOR map");
        for (int index = 0; index < entries; index++) {
            position = scan(bytes, position, depth + 1, itemStart, limits);
            if (position == INCOMPLETE) {
                return INCOMPLETE;
            }
            position = scan(bytes, position, depth + 1, itemStart, limits);
            if (position == INCOMPLETE) {
                return INCOMPLETE;
            }
        }
        return position;
    }

    private static Header header(byte[] bytes, int offset) throws AgentProtocolException {
        if (offset >= bytes.length) {
            return null;
        }
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
        if (bytes.length - offset - 1 < argumentBytes) {
            return null;
        }
        long argument = 0;
        for (int index = 0; index < argumentBytes; index++) {
            argument = (argument << 8) | (bytes[offset + 1 + index] & 0xffL);
        }
        return new Header(majorType, additionalInfo, argument, offset + 1 + argumentBytes);
    }

    private static int advance(
            byte[] bytes,
            int position,
            int length,
            int itemStart,
            AgentProtocolLimits limits
    ) throws AgentProtocolException {
        long end = (long) position + length;
        if (end - itemStart > limits.maxMessageBytes()) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, "CBOR item exceeds configured limit");
        }
        return end > bytes.length ? INCOMPLETE : (int) end;
    }

    private static int length(long value, int maximum, String name) throws AgentProtocolException {
        if (value < 0 || value > maximum) {
            throw failure(AgentProtocolException.Reason.LIMIT_EXCEEDED, name + " exceeds configured limit");
        }
        return (int) value;
    }

    private static void checkEntries(int entries, AgentProtocolLimits limits) throws AgentProtocolException {
        if (entries > limits.maxCollectionEntries()) {
            throw failure(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "CBOR collection exceeds configured limit");
        }
    }

    private static AgentProtocolException failure(AgentProtocolException.Reason reason, String message) {
        return new AgentProtocolException(reason, message);
    }

    private record Header(int majorType, int additionalInfo, long argument, int end) {
    }
}
