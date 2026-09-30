package pro.deta.orion.git.s3;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;

import java.io.IOException;

/** Explicit data-plane stub; holds no resources and never owns the provider's S3 client. */
final class S3GitStorage implements GitStorageApi {
    @Override
    public PackDataStorage newPack(PackId packId) throws IOException {
        throw unsupported();
    }

    @Override
    public <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader) throws IOException {
        throw unsupported();
    }

    @Override
    public boolean exists(PackId packId) throws IOException {
        throw unsupported();
    }

    @Override
    public void close() {
    }

    private static IOException unsupported() {
        return new IOException("S3 Git pack storage is not implemented (metadata bootstrap only)");
    }
}
