package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.IOException;
import java.util.Objects;

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
 * inflated content to a consumer. ZlibInflatedInputStream owns zlib decoding and boundary validation.
 */
public abstract sealed class CompressedGitObjectRead<R> implements GitObjectRead<R>
        permits HashedGitObjectRead, ContentGitObjectRead {
    @Override
    public final R read(ObjectType type, long inflatedSize, BufferedByteInput rawSource) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(rawSource, "rawSource");
        if (inflatedSize < 0) {
            throw new IOException("Negative inflated object size");
        }
        R value = null;
        try (var inflated = new ZlibInflatedInputStream(rawSource, inflatedSize)) {
            value = Objects.requireNonNull(readDecompressed(type, inflatedSize,
                    new InputStreamBufferedByteInput(inflated)), "reader result");
            byte[] discard = new byte[8192];
            while (inflated.read(discard) != -1) {
                // Validate any content the consumer did not need to read.
            }
            return value;
        } catch (IOException | RuntimeException | Error failure) {
            if (value instanceof AutoCloseable resource) {
                try {
                    resource.close();
                } catch (Throwable cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            throw failure;
        }
    }

    protected abstract R readDecompressed(ObjectType type, long size, BufferedByteInput content)
            throws IOException;
}
