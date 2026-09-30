package pro.deta.orion.git.s3;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Metadata bootstrap does not implement Git refs or object indexing. */
final class S3GitIndex implements GitIndexApi {
    @Override
    public GitIndexAccess createAccess(Set<RefId> refs) throws IOException {
        throw unsupported();
    }

    @Override
    public GitIndexAccess createAccess(List<RefUpdate> updates) throws IOException {
        throw unsupported();
    }

    private static IOException unsupported() {
        return new IOException("S3 Git index and refs are not implemented (metadata bootstrap only)");
    }

    @Override
    public void close() {
    }

    @Override
    public GitHashAlgorithm hashAlgorithm() {
        return GitHashAlgorithm.SHA1;
    }
}
