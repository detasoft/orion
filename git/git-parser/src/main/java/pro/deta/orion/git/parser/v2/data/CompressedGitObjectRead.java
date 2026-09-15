package pro.deta.orion.git.parser.v2.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Shared decompression branch of GitObjectRead, separate from RawGitObjectRead's direct byte access.
 * Owns common type and inflated-size metadata. readDecompressed is the shared hook for bounded, streaming
 * decompression: the hash subclass consumes inflated chunks for hashing, while the content subclass exposes
 * them to readers. It must not require a whole-object buffer or retain content for future pack entries.
 * Offsets passed to the hook address inflated payload bytes; returned counts follow ContentGitObjectRead's
 * buffer and EOF contract. Positional reads may repeat decompression from the retained original pack.
 *
 * <p>For OFS_DELTA and REF_DELTA, decompression yields instructions, not reconstructed object content.
 * Base lookup and delta application remain in the resolver. Raw copying never invokes this branch.
 * Constructor fields describe metadata; the shared decompression hook remains a placeholder until backing
 * reads and inflater lifecycle are implemented. Subclasses retain responsibility for their result resources.
 */
public abstract sealed class CompressedGitObjectRead implements GitObjectRead
        permits HashedGitObjectRead, ContentGitObjectRead {
    private final ObjectType type;
    private final long size;

    protected CompressedGitObjectRead(ObjectType type, long size) {
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
    }

    @Override
    public final ObjectType type() {
        return type;
    }

    @Override
    public final long size() {
        return size;
    }

    protected final int readDecompressed(long offset, ByteBuffer destination) throws IOException {
        throw new UnsupportedOperationException("Object decompression is not implemented");
    }
}
