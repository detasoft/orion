package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ResolvedGitObjectRead<R> extends CompressedGitObjectRead<R> {
    private final GitStorageApi storage;
    private final GitObjectRead<R> consumer;
    private final GitIndexAccess index;
    private final Optional<PackId> pendingPack;

    public ResolvedGitObjectRead(GitStorageApi storage, GitIndexAccess index, GitObjectRead<R> consumer) {
        this(storage, index, Optional.empty(), consumer);
    }

    public ResolvedGitObjectRead(GitStorageApi storage, GitIndexAccess index, Optional<PackId> pendingPack,
                                  GitObjectRead<R> consumer) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.index = Objects.requireNonNull(index, "index");
        this.pendingPack = Objects.requireNonNull(pendingPack, "pendingPack");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
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
        return readDelta(content, readBase(id), consumer);
    }

    private Base readBase(ObjectId id) throws IOException {
        Set<ObjectId> path = new HashSet<>();
        Deque<IndexedObject> deltas = new ArrayDeque<>();
        IndexedObject location;
        while (true) {
            if (!path.add(id)) {
                throw new IOException("Cyclic delta base: " + id.toHex());
            }
            location = findBase(id);
            if (location.delta().isEmpty()) {
                break;
            }
            deltas.push(location);
            id = location.delta().orElseThrow().baseId();
        }
        Base base = GitObjectRead.read(storage, location, new ContentGitObjectRead<>(ResolvedGitObjectRead::readBytes));
        while (!deltas.isEmpty()) {
            Base previous = base;
            base = GitObjectRead.read(storage, deltas.pop(), new ContentGitObjectRead<>((type, size, unused, input) ->
                    readDelta(input, previous, ResolvedGitObjectRead::readBytes)));
        }
        return base;
    }

    private IndexedObject findBase(ObjectId id) throws IOException {
        IndexedObject candidate = null;
        if (pendingPack.isPresent()) {
            candidate = index.findObject(pendingPack.orElseThrow(), id).orElse(null);
            if (candidate != null && candidate.delta().isEmpty()) {
                return candidate;
            }
        }
        for (IndexedObject object : index.locations(id)) {
            if (object.delta().isEmpty()) {
                return object;
            }
            if (candidate == null) {
                candidate = object;
            }
        }
        if (candidate == null) {
            throw new IOException("Missing delta base: " + id.toHex());
        }
        return candidate;
    }

    private static Base readBytes(GitObjectType type, long size, Optional<ObjectId> unused,
                                  BufferedByteInputV2 input) throws IOException {
        if (size > Integer.MAX_VALUE - 8) {
            throw new IOException("Delta base is too large for in-memory resolution");
        }
        return new Base(type, input.readBytes((int) size));
    }

    private static <T> T readDelta(BufferedByteInputV2 content, Base base, GitObjectRead<T> consumer)
            throws IOException {
        DeltaByteSource delta = new DeltaByteSource(content, base.bytes());
        T value = null;
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
