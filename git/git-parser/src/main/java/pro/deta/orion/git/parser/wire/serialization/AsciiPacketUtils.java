package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.v2.pkt.GitPktLine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.MAX_PKT_LINE_LENGTH;
import static pro.deta.orion.git.parser.v2.pkt.GitPktLine.PKT_LINE_HEADER_SIZE;

public class AsciiPacketUtils {
    public static List<GitPktLine> encodeAsciiPackets(List<String> payloads, boolean sidebandAll) {
        List<GitPktLine> packets = new ArrayList<>();
        for (String payload : payloads) {
            packets.add(encodeAsciiPacket(payload, sidebandAll));
        }
        packets.add(GitPktLine.Control.FLUSH);
        return List.copyOf(packets);
    }

    public static GitPktLine.Data encodeAsciiPacket(String payload, boolean sidebandAll) {
        int sidebandLength = sidebandAll ? 1 : 0;
        validateAsciiPacket(payload, sidebandLength);
        return new GitPktLine.Data(payload.getBytes(StandardCharsets.US_ASCII));
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

}
