package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.IOException;

/**
 * Shared streaming decompression processor, separate from RawGitObjectRead's direct byte processing.
 * read owns a per-invocation inflater, exposes its bounded inflated source to readDecompressed, drains and
 * validates the remaining zlib stream and declared length, and then returns the result. It releases only
 * inflater resources, never the provider's raw source. No whole-object buffer or cross-entry cache is required.
 * On failure after a resource-bearing result was produced, the implementation must release that unreturned
 * result's owned resources as well. Processing state belongs to the invocation, not to this reusable handler.
 *
 * <p>For OFS_DELTA and REF_DELTA, readDecompressed receives delta instructions. It never fetches bases or
 * restores an object from a delta. HashedGitObjectRead hashes full content; ContentGitObjectRead delegates
 * inflated content to a consumer. The decompression and resource-management method remains a placeholder.
 */
public abstract sealed class CompressedGitObjectRead<R> implements GitObjectRead<R>
        permits HashedGitObjectRead, ContentGitObjectRead {
    @Override
    public final R read(ObjectType type, long inflatedSize, BufferedByteInput rawSource) throws IOException {
        throw new UnsupportedOperationException("Object decompression is not implemented");
    }

    protected abstract R readDecompressed(ObjectType type, long size, BufferedByteInput content)
            throws IOException;
}
