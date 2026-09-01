package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;

import java.io.IOException;
import java.util.List;

public final class PacketListSerialization implements OutputSerialization {
    private final List<byte[]> packets;
    private int packetIndex;
    private int packetOffset;

    public PacketListSerialization(List<byte[]> packets) {
        this.packets = packets;
    }

    @Override
    public void writeTo(GitBlockingWireTransport wire) throws IOException {
        while (packetIndex < packets.size()) {
            byte[] packet = packets.get(packetIndex);
            if (packetOffset == 0) {
                OutputSerialization.writeBytes(wire, packet);
            } else {
                byte[] remaining = new byte[packet.length - packetOffset];
                System.arraycopy(packet, packetOffset, remaining, 0, remaining.length);
                OutputSerialization.writeBytes(wire, remaining);
            }
            packetIndex++;
            packetOffset = 0;
        }
        wire.flush();
    }
}
