package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ResolvedGitObjectRead<R> extends CompressedGitObjectRead<R> {
    private final GitStorageApi storage;
    private final GitObjectRead<R> consumer;
    private final Set<ObjectId> activeBases;

    public ResolvedGitObjectRead(GitStorageApi storage, GitObjectRead<R> consumer) {
        this(storage, consumer, null);
    }

    private ResolvedGitObjectRead(GitStorageApi storage, GitObjectRead<R> consumer, Set<ObjectId> activeBases) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.activeBases = activeBases;
    }

    @Override
    protected R readDecompressed(GitObjectType type, long size, Optional<ObjectId> baseId,
                                 BufferedByteInputV2 content) throws IOException {
        if (type == GitObjectType.OFS_DELTA) {
            throw new IllegalStateException("not yet supported");
        }
        if (type != GitObjectType.REF_DELTA) {
            return consumer.read(type, size, Optional.empty(), content);
        }
        ObjectId id = baseId.orElseThrow(() -> new IOException("REF_DELTA has no base ObjectId"));
        Set<ObjectId> path = activeBases == null ? new HashSet<>() : activeBases;
        if (!path.add(id)) {
            throw new IOException("Cyclic delta base: " + id.toHex());
        }
        Base base;
        try {
            ResolvedGitObjectRead<Base> baseReader = new ResolvedGitObjectRead<>(storage,
                    (baseType, baseSize, unused, input) -> {
                if (baseSize > Integer.MAX_VALUE - 8) {
                    throw new IOException("Delta base is too large for in-memory resolution");
                }
                return new Base(baseType, input.readBytes((int) baseSize));
            }, path);
            base = storage.readObject(id, baseReader)
                    .orElseThrow(() -> new IOException("Missing delta base: " + id.toHex()));
        } finally {
            path.remove(id);
        }
        DeltaByteSource delta = new DeltaByteSource(content, base.bytes());
        R value = null;
        try (BufferedByteInputV2 restored = new BufferedByteInputV2(delta)) {
            value = Objects.requireNonNull(consumer.read(base.type(), delta.size(), Optional.empty(), restored),
                    "reader result");
            ByteBuffer remaining;
            while ((remaining = restored.buffer()) != null) {
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

    private record Base(GitObjectType type, byte[] bytes) {}

}
