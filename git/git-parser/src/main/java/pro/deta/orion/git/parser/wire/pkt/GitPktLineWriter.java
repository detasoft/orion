package pro.deta.orion.git.parser.wire.pkt;

import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;

public final class GitPktLineWriter {
    private static final int MAX_PAYLOAD_LENGTH = MAX_PKT_LINE_LENGTH - PKT_LINE_HEADER_SIZE;

    public byte[] writeDataHeader(int payloadLength) {
        if (payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Pkt-line payload exceeds Git pkt-line limit");
        }
        return lengthHeader(payloadLength + PKT_LINE_HEADER_SIZE);
    }

    public byte[] writeFlush() {
        return writeControlPacket(0);
    }

    public byte[] writeDelimiter() {
        return writeControlPacket(1);
    }

    public byte[] writeResponseEnd() {
        return writeControlPacket(2);
    }

    private byte[] writeControlPacket(int packetLength) {
        return lengthHeader(packetLength);
    }

    private static byte[] lengthHeader(int packetLength) {
        return new byte[]{
                hexDigit((packetLength >>> 12) & 0x0f),
                hexDigit((packetLength >>> 8) & 0x0f),
                hexDigit((packetLength >>> 4) & 0x0f),
                hexDigit(packetLength & 0x0f)
        };
    }
}
