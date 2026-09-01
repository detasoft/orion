package pro.deta.orion.git.parser.wire.serialization;

import pro.deta.orion.git.parser.wire.GitBlockingWireTransport;

import java.io.IOException;

public interface OutputSerialization {
    void writeTo(GitBlockingWireTransport wire) throws IOException;

    static void writeBytes(GitBlockingWireTransport wire, byte[] bytes) throws IOException {
        wire.writeRaw(bytes);
    }
}
