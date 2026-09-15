package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.v2.pkt.GitPktLine;
import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;

import java.io.IOException;

public final class PktLineSerialization implements OutputSerialization {
    private final GitPktLine packet;
    private boolean written;

    public PktLineSerialization(byte[] payload) {
        this.packet = new GitPktLine.Data(payload.clone());
    }

    @Override
    public void writeTo(GitBlockingWireTransport wire) throws IOException {
        if (!written) {
            written = true;
            wire.writePacket(packet);
        }
        wire.flush();
    }
}
