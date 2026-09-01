package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;

import java.io.IOException;
import java.util.List;

import static pro.deta.orion.git.parser.wire.serialization.AsciiPacketUtils.encodeAsciiPackets;


public final class AsciiPacketSequenceSerialization implements OutputSerialization {
    private final List<String> payloads;

    public AsciiPacketSequenceSerialization(List<String> payloads) {
        this.payloads = List.copyOf(payloads);
    }

    @Override
    public void writeTo(GitBlockingWireTransport wire) throws IOException {
        PacketListSerialization packets = new PacketListSerialization(encodeAsciiPackets(payloads, false));
        packets.writeTo(wire);
    }
}

