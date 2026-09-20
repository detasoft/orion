package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.PackObjectLocation;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ResolvedGitObjectRead<R> extends CompressedGitObjectRead<R> {
    private final GitStorageApi storage;
    private final GitObjectRead<R> consumer;

    public ResolvedGitObjectRead(GitStorageApi storage, GitObjectRead<R> consumer) {
        this.storage = Objects.requireNonNull(storage, "storage");
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
        Deque<PackObjectLocation> deltas = new ArrayDeque<>();
        PackObjectLocation location;
        while (true) {
            if (!path.add(id)) {
                throw new IOException("Cyclic delta base: " + id.toHex());
            }
            List<PackObjectLocation> found = storage.locateObjects(List.of(id));
            if (found.isEmpty()) {
                throw new IOException("Missing delta base: " + id.toHex());
            }
            location = found.getFirst();
            GitObjectType type = location.entry().type();
            if (type != GitObjectType.REF_DELTA && type != GitObjectType.OFS_DELTA) {
                break;
            }
            deltas.push(location);
            id = location.baseId().orElseThrow(() -> new IOException("Delta has no base ObjectId"));
        }
        Base base = storage.readObject(location, new ContentGitObjectRead<>(ResolvedGitObjectRead::readBytes));
        while (!deltas.isEmpty()) {
            Base previous = base;
            base = storage.readObject(deltas.pop(), new ContentGitObjectRead<>((type, size, unused, input) ->
                    readDelta(input, previous, ResolvedGitObjectRead::readBytes)));
        }
        return base;
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
