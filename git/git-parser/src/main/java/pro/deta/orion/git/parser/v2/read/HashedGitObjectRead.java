package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;

/**
 * Processes a full object's inflated bytes into its canonical ObjectId without retaining its content.
 * Inherited read performs decompression, then readDecompressed hashes the canonical type/size header and
 * content incrementally. Types are COMMIT, TREE, BLOB, or TAG. Delta types are rejected: a hash of instructions
 * is not the reconstructed object's ID, which must be computed by the resolver after applying its base.
 * No ObjectId, type, size, or input handle is stored in this processor. Hashing remains a placeholder.
 */
public final class HashedGitObjectRead extends CompressedGitObjectRead<ObjectId> {
    @Override
    protected ObjectId readDecompressed(ObjectType type, long size, BufferedByteInput content)
            throws IOException {
        throw new UnsupportedOperationException("Object content hashing is not implemented");
    }
}
