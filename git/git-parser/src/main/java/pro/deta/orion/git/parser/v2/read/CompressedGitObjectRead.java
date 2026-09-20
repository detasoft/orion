package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

public abstract sealed class CompressedGitObjectRead<R> implements GitObjectRead<R>
        permits HashedGitObjectRead, ContentGitObjectRead, ResolvedGitObjectRead {
    @Override
    public final R read(GitObjectType type, long inflatedSize, Optional<ObjectId> baseId,
            BufferedByteInputV2 rawSource) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(baseId, "baseId");
        Objects.requireNonNull(rawSource, "rawSource");
        if (inflatedSize < 0) {
            throw new IOException("Negative inflated object size");
        }
        R value = null;
        try (BufferedByteInputV2 inflated = new BufferedByteInputV2(new ZlibByteSource(rawSource, inflatedSize))) {
            value = Objects.requireNonNull(readDecompressed(type, inflatedSize, baseId, inflated), "reader result");
            ByteBuffer remaining;
            while ((remaining = inflated.buffer()) != null) {
                remaining.position(remaining.limit());
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
            BufferedByteInputV2 content)
            throws IOException;
}
