package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.util.ZlibInflatedInputStream;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public abstract sealed class CompressedGitObjectRead<R> implements GitObjectRead<R>
        permits HashedGitObjectRead, ContentGitObjectRead, ResolvedGitObjectRead {
    @Override
    public final R read(GitObjectType type, long inflatedSize, Optional<ObjectId> baseId,
            BufferedByteInput rawSource) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(baseId, "baseId");
        Objects.requireNonNull(rawSource, "rawSource");
        if (inflatedSize < 0) {
            throw new IOException("Negative inflated object size");
        }
        R value = null;
        try (var inflated = new ZlibInflatedInputStream(rawSource, inflatedSize)) {
            value = Objects.requireNonNull(readDecompressed(type, inflatedSize, baseId,
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

    protected abstract R readDecompressed(GitObjectType type, long size, Optional<ObjectId> baseId,
            BufferedByteInput content)
            throws IOException;
}
