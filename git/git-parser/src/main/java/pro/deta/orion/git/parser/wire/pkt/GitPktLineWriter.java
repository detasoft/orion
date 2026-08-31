package pro.deta.orion.git.parser.wire.pkt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;
import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;

public final class GitPktLineWriter {
    private static final int MAX_PAYLOAD_LENGTH = MAX_PKT_LINE_LENGTH - PKT_LINE_HEADER_SIZE;
    private static final int SIDEBAND_HEADER_SIZE = PKT_LINE_HEADER_SIZE + 1;
    private static final int MAX_SIDEBAND_PAYLOAD_LENGTH =
            MAX_PKT_LINE_LENGTH - SIDEBAND_HEADER_SIZE;

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

    public byte[] writeSidebandHeader(
            int channel,
            int payloadLength) {
        if (payloadLength > MAX_SIDEBAND_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "Sideband payload exceeds Git pkt-line limit");
        }
        int packetLength = SIDEBAND_HEADER_SIZE + payloadLength;
        byte[] header = new byte[SIDEBAND_HEADER_SIZE];
        writeHeader(header, packetLength);
        header[PKT_LINE_HEADER_SIZE] = (byte) channel;
        return header;
    }

    public List<byte[]> writeSidebandPackets(
            int channel,
            byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        List<byte[]> packets = new ArrayList<>();
        int payloadOffset = 0;
        int remaining = payload.length;
        do {
            int chunkLength = Math.min(
                    remaining,
                    MAX_SIDEBAND_PAYLOAD_LENGTH);
            int packetLength = SIDEBAND_HEADER_SIZE + chunkLength;
            byte[] packet = new byte[packetLength];
            writeHeader(packet, packetLength);
            packet[PKT_LINE_HEADER_SIZE] = (byte) channel;
            System.arraycopy(
                    payload,
                    payloadOffset,
                    packet,
                    SIDEBAND_HEADER_SIZE,
                    chunkLength);
            packets.add(packet);
            payloadOffset += chunkLength;
            remaining -= chunkLength;
        } while (remaining > 0);
        return List.copyOf(packets);
    }

    private byte[] writeControlPacket(int packetLength) {
        return lengthHeader(packetLength);
    }

    private static byte[] lengthHeader(int packetLength) {
        byte[] header = new byte[PKT_LINE_HEADER_SIZE];
        writeHeader(header, packetLength);
        return header;
    }

    private static void writeHeader(
            byte[] output,
            int packetLength) {
        output[0] = hexDigit((packetLength >>> 12) & 0x0f);
        output[1] = hexDigit((packetLength >>> 8) & 0x0f);
        output[2] = hexDigit((packetLength >>> 4) & 0x0f);
        output[3] = hexDigit(packetLength & 0x0f);
    }
}
