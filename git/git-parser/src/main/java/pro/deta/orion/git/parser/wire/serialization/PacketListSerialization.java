package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;
import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.v2.pkt.SideBand;

import java.io.IOException;
import java.util.List;

public final class PacketListSerialization implements OutputSerialization {
    private final List<GitPktLine> packets;
    private int packetIndex;
    private final SideBand sideBand;

    public PacketListSerialization(List<GitPktLine> packets) {
        this(packets, SideBand.NONE);
    }

    public PacketListSerialization(List<GitPktLine> packets, SideBand sideBand) {
        this.packets = packets;
        this.sideBand = sideBand;
    }

    @Override
    public void writeTo(GitBlockingWireTransport wire) throws IOException {
        while (packetIndex < packets.size()) {
            wire.writePacket(packets.get(packetIndex), sideBand);
            packetIndex++;
        }
        wire.flush();
    }
}
