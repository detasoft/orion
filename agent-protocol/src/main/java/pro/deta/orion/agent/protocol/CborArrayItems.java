package pro.deta.orion.agent.protocol;

import java.util.ArrayList;
import java.util.List;

final class CborArrayItems {
    private CborArrayItems() {
    }

    static List<Slice> parse(byte[] encoded, AgentProtocolLimits limits) throws AgentProtocolException {
        if (encoded.length == 0 || ((encoded[0] & 0xff) >>> 5) != 4) {
            throw malformed("CBOR item must be an array");
        }
        Header header = header(encoded);
        int position = header.end();
        List<Slice> items = new ArrayList<>();
        if (header.indefinite()) {
            while (true) {
                if (position >= encoded.length) {
                    throw malformed("Incomplete CBOR array");
                }
                if ((encoded[position] & 0xff) == 0xff) {
                    position++;
                    break;
                }
                checkEntries(items.size() + 1, limits);
                position = addItem(encoded, position, items, limits);
            }
        } else {
            int count = checkedCount(header.count(), limits);
            for (int index = 0; index < count; index++) {
                position = addItem(encoded, position, items, limits);
            }
        }
        if (position != encoded.length) {
            throw malformed("CBOR array has trailing bytes");
        }
        return List.copyOf(items);
    }

    private static int addItem(
            byte[] encoded,
            int position,
            List<Slice> items,
            AgentProtocolLimits limits
    ) throws AgentProtocolException {
        int length = CborItemScanner.itemLength(encoded, position, limits);
        if (length < 0) {
            throw malformed("Incomplete CBOR array item");
        }
        items.add(new Slice(position, position + length));
        return position + length;
    }

    private static Header header(byte[] encoded) throws AgentProtocolException {
        int additionalInfo = encoded[0] & 0x1f;
        if (additionalInfo >= 28 && additionalInfo <= 30) {
            throw malformed("Reserved CBOR additional information");
        }
        if (additionalInfo < 24) {
            return new Header(additionalInfo, 1, false);
        }
        if (additionalInfo == 31) {
            return new Header(0, 1, true);
        }
        int argumentBytes = 1 << (additionalInfo - 24);
        if (encoded.length < 1 + argumentBytes) {
            throw malformed("Incomplete CBOR array header");
        }
        long count = 0;
        for (int index = 0; index < argumentBytes; index++) {
            count = (count << 8) | (encoded[1 + index] & 0xffL);
        }
        return new Header(count, 1 + argumentBytes, false);
    }

    private static int checkedCount(long count, AgentProtocolLimits limits) throws AgentProtocolException {
        if (count < 0 || count > limits.maxCollectionEntries()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "CBOR array exceeds configured entry limit");
        }
        return (int) count;
    }

    private static void checkEntries(int count, AgentProtocolLimits limits) throws AgentProtocolException {
        if (count > limits.maxCollectionEntries()) {
            throw new AgentProtocolException(
                    AgentProtocolException.Reason.LIMIT_EXCEEDED,
                    "CBOR array exceeds configured entry limit");
        }
    }

    private static AgentProtocolException malformed(String message) {
        return new AgentProtocolException(AgentProtocolException.Reason.MALFORMED_CBOR, message);
    }

    record Slice(int from, int to) {
    }

    private record Header(long count, int end, boolean indefinite) {
    }
}
