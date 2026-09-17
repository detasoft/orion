package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.id.PackId;

import java.io.IOException;

/** Supplies caller-owned test stores to parser tests that do not publish repository files. */
public record TestPackBackend(PackByteStore bytes, PackIndex index) implements PackUpload.Backend {
    @Override
    public PackId commit(PackId receivedId) {
        throw new AssertionError("This parser fixture does not publish packs");
    }

    @Override
    public void rollback() throws IOException {
        bytes.close();
    }
}
