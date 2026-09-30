package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;
import pro.deta.orion.git.parser.v2.read.DeltaByteSource;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.Deflater;

/**
 * Resolves pending deltas after checksum validation and adds required external bases to the same pack.
 * An ingestion-local cache reuses restored contents, bounded by both payload bytes and entry count.
 * Oversized and evicted bases retain the ordinary storage read path; pack bytes and IDs are unchanged.
 */
final class GitPackObjectResolver {
    private static final int CACHE_BYTES = 8 * 1024 * 1024;
    private static final int CACHE_ENTRIES = 1024;
    private final LinkedHashMap<ObjectId, Base> bases = new LinkedHashMap<>(16, 0.75f, true);
    private int cachedBytes;
    private final PackId packId;
    private final PackDataStorage bytes;
    private final GitStorageApi storage;
    private final GitIndexAccess index;

    GitPackObjectResolver(PackId packId, PackDataStorage bytes, GitStorageApi storage, GitIndexAccess index) {
        this.packId = packId;
        this.bytes = bytes;
        this.storage = storage;
        this.index = index;
    }

    void resolve(List<PackIngestor.Pending> pending, Map<Long, ObjectId> offsets) throws IOException {
        Map<ObjectId, List<PackIngestor.Pending>> byId = new HashMap<>();
        Map<Long, List<PackIngestor.Pending>> byOffset = new HashMap<>();
        ArrayDeque<PackIngestor.Pending> ready = new ArrayDeque<>();
        for (PackIngestor.Pending object : pending) {
            PackEntry entry = object.entry();
            if (entry.baseOffset().isPresent()) {
                long baseOffset = entry.baseOffset().orElseThrow();
                if (offsets.containsKey(baseOffset)) {
                    ready.add(object);
                } else {
                    byOffset.computeIfAbsent(baseOffset, ignored -> new ArrayList<>()).add(object);
                }
            } else {
                ObjectId baseId = entry.baseId().orElseThrow();
                if (index.findObject(packId, baseId).isPresent() || !index.locations(baseId).isEmpty()) {
                    ready.add(object);
                } else {
                    byId.computeIfAbsent(baseId, ignored -> new ArrayList<>()).add(object);
                }
            }
        }
        int remaining = pending.size();
        while (!ready.isEmpty()) {
            PackIngestor.Pending object = ready.removeFirst();
            PackEntry entry = object.entry();
            ObjectId baseId = entry.baseId().orElseGet(() -> offsets.get(entry.baseOffset().orElseThrow()));
            Base base = base(baseId);
            IndexedObject resolved = storage.readPack(packId, object.storedOffset(), object.compressedSize(),
                    (length, input) -> new ContentGitObjectRead<>((type, size, unused, instructions) -> {
                        DeltaByteSource delta = new DeltaByteSource(instructions, base.content());
                        try (BufferedByteInputV2 restored = new BufferedByteInputV2(delta)) {
                            MessageDigest hash = objectHash(base.type(), delta.size());
                            byte[] content = delta.size() <= CACHE_BYTES ? new byte[(int) delta.size()] : null;
                            int position = 0;
                            ByteBuffer buffer;
                            while ((buffer = restored.buffer()) != null) {
                                if (content != null) {
                                    int count = buffer.remaining();
                                    buffer.duplicate().get(content, position, count);
                                    position += count;
                                }
                                hash.update(buffer);
                            }
                            ObjectId id = new ObjectId(hash.digest());
                            if (content != null) {
                                cache(id, new Base(base.type(), content));
                            }
                            return new IndexedObject(packId, id, base.type(),
                                    delta.size(), object.storedOffset(), object.compressedSize(),
                                    Optional.of(new IndexedObject.Delta(baseId, entry.inflatedSize())));
                        }
                    }).read(GitObjectType.REF_DELTA, entry.inflatedSize(), Optional.of(baseId), input));
            index.addObject(resolved);
            offsets.put(entry.offset(), resolved.objectId());
            remaining--;
            wake(byId, resolved.objectId(), ready);
            wake(byOffset, entry.offset(), ready);
        }
        if (remaining != 0) {
            throw new IOException("Pack contains unresolved delta bases or a cycle");
        }
        for (IndexedObject object : index.objects(packId)) {
            if (object.delta().isEmpty()) {
                continue;
            }
            ObjectId baseId = object.delta().orElseThrow().baseId();
            if (index.findObject(packId, baseId).isEmpty()) {
                appendBase(baseId, base(baseId));
            }
        }
    }

    private Base base(ObjectId id) throws IOException {
        Base cached = bases.get(id);
        if (cached != null) {
            return cached;
        }
        Optional<IndexedObject> local = index.findObject(packId, id);
        ResolvedGitObjectRead<Base> reader = new ResolvedGitObjectRead<>(storage, index,
                Optional.of(packId), GitPackObjectResolver::readBase);
        Base base = local.isPresent() ? GitObjectRead.read(storage, local.orElseThrow(), reader)
                : GitObjectRead.read(storage, index, id, reader)
                        .orElseThrow(() -> new IOException("Missing delta base: " + id));
        cache(id, base);
        return base;
    }

    private void cache(ObjectId id, Base base) {
        if (base.content().length > CACHE_BYTES) {
            return;
        }
        Base previous = bases.remove(id);
        if (previous != null) {
            cachedBytes -= previous.content().length;
        }
        while (!bases.isEmpty() && (cachedBytes + base.content().length > CACHE_BYTES
                || bases.size() >= CACHE_ENTRIES)) {
            cachedBytes -= bases.pollFirstEntry().getValue().content().length;
        }
        bases.put(id, base);
        cachedBytes += base.content().length;
    }

    private static <K> void wake(Map<K, List<PackIngestor.Pending>> waiting, K key,
                                  ArrayDeque<PackIngestor.Pending> ready) {
        List<PackIngestor.Pending> found = waiting.remove(key);
        if (found != null) {
            ready.addAll(found);
        }
    }

    private void appendBase(ObjectId expected, Base base) throws IOException {
        if (!MessageDigest.isEqual(objectHash(base.type(), base.content().length).digest(base.content()),
                expected.toBytes())) {
            throw new IOException("External base ObjectId does not match its content");
        }
        long offset = bytes.size();
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(base.content());
            deflater.finish();
            byte[] compressed = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(compressed);
                if (count == 0) {
                    throw new IOException("Base compression made no progress");
                }
                bytes.write(bytes.size(), ByteBuffer.wrap(compressed, 0, count));
            }
        } finally {
            deflater.end();
        }
        index.addObject(new IndexedObject(packId, expected, base.type(), base.content().length,
                offset, bytes.size() - offset, Optional.empty()));
    }

    private static MessageDigest objectHash(GitObjectType type, long size) {
        MessageDigest hash = GitHashAlgorithm.SHA1.newDigest();
        hash.update((type.name().toLowerCase(Locale.ROOT) + " " + size + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        return hash;
    }

    private static Base readBase(GitObjectType type, long size, Optional<ObjectId> unused,
                                  BufferedByteInputV2 input) throws IOException {
        if (size > Integer.MAX_VALUE - 8) {
            throw new IOException("Delta base is too large for in-memory resolution");
        }
        return new Base(type, input.readBytes((int) size));
    }

    private record Base(GitObjectType type, byte[] content) {}
}
