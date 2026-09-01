package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;

import java.io.IOException;

import static pro.deta.orion.git.parser.wire.GitNativeUtils.hexDigit;
import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public final class PktLineSerialization implements OutputSerialization {
    private final byte[] payload;
    private final int packetLength;
    private int packetOffset;

    public PktLineSerialization(byte[] payload, int packetLength) {
        this.payload = payload.clone();
        this.packetLength = packetLength;
    }

    @Override
    public void writeTo(GitBlockingWireTransport wire) throws IOException {
        byte[] packet = new byte[packetLength - packetOffset];
        for (int index = 0; index < packet.length; index++) {
            packet[index] = byteAt(packetOffset + index);
        }
        packetOffset = packetLength;
        OutputSerialization.writeBytes(wire, packet);
        wire.flush();
    }

    private byte byteAt(int offset) {
        if (offset < PKT_LINE_HEADER_SIZE) {
            int shift = (PKT_LINE_HEADER_SIZE - 1 - offset) * 4;
            return hexDigit((packetLength >>> shift) & 0x0f);
        }
        return payload[offset - PKT_LINE_HEADER_SIZE];
    }
}
