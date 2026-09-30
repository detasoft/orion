package pro.deta.orion.git.s3;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;

import java.io.IOException;

/** Metadata bootstrap does not implement Git refs or object indexing. */
final class S3GitIndex implements GitIndexApi {
    @Override
    public GitIndexAccess createAccess() throws IOException {
        throw new IOException("S3 Git index and refs are not implemented (metadata bootstrap only)");
    }

    @Override
    public GitHashAlgorithm hashAlgorithm() {
        return GitHashAlgorithm.SHA1;
    }
}
