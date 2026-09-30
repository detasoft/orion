package pro.deta.orion.git.s3;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.util.HashMap;
import java.util.Map;

/** Opens independent storage accesses; active accesses borrow the shared transport after owner close. */
final class S3GitStorageApi implements GitStorageApi {
    final S3RepositoryObjects objects;
    final Map<PackId, S3GitStorage.Writer> writers = new HashMap<>();
    private boolean closed;

    S3GitStorageApi(S3RepositoryObjects objects) { this.objects = objects; }

    @Override
    public synchronized GitStorageAccess createAccess() throws java.io.IOException {
        if (closed) throw new java.nio.channels.ClosedChannelException();
        return new S3GitStorage(this);
    }

    @Override
    public synchronized void close() { closed = true; }
}
