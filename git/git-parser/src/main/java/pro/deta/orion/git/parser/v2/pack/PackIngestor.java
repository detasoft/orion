package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Streams compressed entries into storage and immediately indexes verified full objects privately.
 * Delta resolution starts only after the input checksum passes. The returned metadata describes a
 * self-contained Git export; the caller publishes it in the index. Failures leave only private rows
 * and orphan bytes. The caller owns the input; this one-shot ingestor closes its writable byte handle.
 * Internal format v1 is the eight-byte ORPK/version header followed by compressed entry payloads;
 * boundaries, resolved types and delta bases live exclusively in the repository index.
 */
public final class PackIngestor implements AutoCloseable {
    private final BufferedByteInputV2 input;
    private final GitStorageApi storage;
    private final GitIndexAccess index;
    private final PackId packId;
    private boolean started;

    public PackIngestor(BufferedByteInputV2 input, GitStorageApi storage, GitIndexAccess index) {
        this.input = Objects.requireNonNull(input, "input");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.index = Objects.requireNonNull(index, "index");
        this.packId = index.packId().orElseThrow(() -> new IllegalArgumentException("Pack access required"));
    }

    public PackMetadata ingest() throws IOException {
        if (started) {
            throw new IllegalStateException("Pack ingestion has already started or closed");
        }
        started = true;
        Map<Long, ObjectId> offsets = new HashMap<>();
        List<Pending> pending = new ArrayList<>();
        try (PackDataStorage bytes = storage.newPack(packId); PackReader reader = new PackReader(input)) {
            bytes.write(0, ByteBuffer.allocate(8).putInt(0x4f52504b).putInt(1).flip());
            boolean ended = false;
            long start = 8;
            while (!ended) {
                switch (reader.next()) {
                    case PackReadStep.Entry entry -> start = bytes.size();
                    case PackReadStep.Bytes content -> bytes.write(bytes.size(), content.data());
                    case PackReadStep.EntryEnd end -> {
                        PackEntry entry = end.metadata();
                        if (bytes.size() - start != end.compressedSize()) {
                            throw new IOException("Stored entry length does not match input");
                        }
                        if (entry.type() == GitObjectType.OFS_DELTA || entry.type() == GitObjectType.REF_DELTA) {
                            pending.add(new Pending(entry, start, end.compressedSize()));
                        } else {
                            ObjectId id = storage.readPack(packId, start, end.compressedSize(),
                                    (length, content) -> new HashedGitObjectRead().read(entry.type(),
                                            entry.inflatedSize(), Optional.empty(), content));
                            index.addObject(new IndexedObject(packId, id, entry.type(), entry.inflatedSize(),
                                    start, end.compressedSize(), Optional.empty()));
                            offsets.put(entry.offset(), id);
                        }
                    }
                    case PackReadStep.End end -> ended = true;
                }
            }
            new GitPackObjectResolver(packId, bytes, storage, index).resolve(pending, offsets);
            List<IndexedObject> objects = index.objects(packId);
            validateExport(objects);
            PackChecksum checksum;
            long size;
            try (PackWriter writer = new PackWriter(
                    new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream()), objects.size())) {
                writer.writeObjects(storage, objects);
                checksum = writer.finish();
                size = writer.size();
            }
            bytes.flush();
            return new PackMetadata(packId, checksum, packId.toString(), objects.size(), size);
        }
    }

    private static void validateExport(List<IndexedObject> objects) throws IOException {
        Map<ObjectId, IndexedObject> byId = new HashMap<>();
        for (IndexedObject object : objects) {
            if (byId.putIfAbsent(object.objectId(), object) != null) {
                throw new IOException("Pack contains duplicate objects");
            }
        }
        Set<ObjectId> complete = new HashSet<>();
        for (IndexedObject object : objects) {
            Set<ObjectId> path = new HashSet<>();
            while (!complete.contains(object.objectId())) {
                if (!path.add(object.objectId())) {
                    throw new IOException("Pack contains a delta cycle");
                }
                if (object.delta().isEmpty()) {
                    break;
                }
                object = byId.get(object.delta().orElseThrow().baseId());
                if (object == null) {
                    throw new IOException("Pack is missing a delta base");
                }
            }
            complete.addAll(path);
        }
    }

    @Override
    public void close() {
        started = true;
    }

    record Pending(PackEntry entry, long storedOffset, long compressedSize) {}
}
