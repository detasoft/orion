package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;

/**
 * Test backend with actual object payloads and a ref snapshot behind the final storage facade.
 * Reads invoke the caller's reader with bounded, valid zlib bytes generated from the stored payload.
 * Tests can observe backend access and introduce read failures without replacing facade methods.
 */
public final class InMemoryGitStorage {
    private final Map<ObjectId, Stored> objects = new HashMap<>();
    public final List<ObjectId> lookups = new ArrayList<>();
    public RefsSnapshot refs = new RefsSnapshot(Map.of(), new Head.Symbolic(new RefId("refs/heads/main")));
    public int snapshots;
    public IOException failure;
    public final GitStorageApi api = new GitStorageApi(new GitObjectStorage() {
        @Override
        <R> Optional<R> read(ObjectId id, GitObjectRead<R> reader) throws IOException {
            lookups.add(id);
            if (failure != null) {
                throw failure;
            }
            Stored object = objects.get(id);
            if (object == null) {
                return Optional.empty();
            }
            var compressed = new ByteArrayOutputStream();
            try (var output = new DeflaterOutputStream(compressed)) {
                output.write(object.payload());
            }
            try (var source = new InputStreamBufferedByteInput(
                    new ByteArrayInputStream(compressed.toByteArray()))) {
                R value = Objects.requireNonNull(reader.read(object.type(), object.payload().length,
                        object.baseId(), source), "reader result");
                source.readBytes(source.available());
                return Optional.of(value);
            }
        }
    }, new GitRefsStorage() {
        @Override
        RefsSnapshot snapshot() {
            snapshots++;
            return refs;
        }
    });

    public void put(ObjectId id, ObjectType type, Optional<ObjectId> baseId, byte[] payload) {
        objects.put(id, new Stored(type, baseId, payload.clone()));
    }

    private record Stored(ObjectType type, Optional<ObjectId> baseId, byte[] payload) {}
}
