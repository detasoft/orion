package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;
import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public class AsciiPacketUtils {
    public static List<byte[]> encodeAsciiPackets(List<String> payloads, boolean sidebandAll) {
        List<byte[]> packets = new ArrayList<>();
        for (String payload : payloads) {
            packets.add(encodeAsciiPacket(payload, sidebandAll));
        }
        packets.add(new byte[]{'0', '0', '0', '0'});
        return List.copyOf(packets);
    }

    public static byte[] encodeAsciiPacket(String payload, boolean sidebandAll) {
        int sidebandLength = sidebandAll ? 1 : 0;
        validateAsciiPacket(payload, sidebandLength);
        int packetLength = payload.length() + PKT_LINE_HEADER_SIZE + sidebandLength;
        byte[] packet = new byte[packetLength];
        writeHeader(packet, packetLength);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.US_ASCII);
        int payloadOffset = PKT_LINE_HEADER_SIZE;
        if (sidebandAll) {
            packet[payloadOffset] = GitBlockingWireTransport.SideBandChannel.DATA.wireValue();
            payloadOffset++;
        }
        System.arraycopy(payloadBytes, 0, packet, payloadOffset, payloadBytes.length);
        return packet;
    }

    public static void validateAsciiPacket(String payload, int extraPayloadBytes) {
        for (int index = 0; index < payload.length(); index++) {
            if (payload.charAt(index) > 0x7f) {
                throw new IllegalArgumentException("Git wire-line response must be ASCII");
            }
        }
        if (payload.length() + extraPayloadBytes + PKT_LINE_HEADER_SIZE > MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException("Git wire-line exceeds maximum length");
        }
    }

    public static void writeHeader(byte[] output, int packetLength) {
        output[0] = hexDigit((packetLength >>> 12) & 0x0f);
        output[1] = hexDigit((packetLength >>> 8) & 0x0f);
        output[2] = hexDigit((packetLength >>> 4) & 0x0f);
        output[3] = hexDigit(packetLength & 0x0f);
    }
}
