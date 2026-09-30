package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.Deflater;

/** Resolves pending deltas after checksum validation and adds required external bases to the same pack. */
final class GitPackObjectResolver {
    private final PackId packId;
    private final PackDataStorage bytes;
    private final GitStorageApi storage;
    private final GitIndexApi index;

    GitPackObjectResolver(PackId packId, PackDataStorage bytes, GitStorageApi storage, GitIndexApi index) {
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
            Optional<IndexedObject> local = index.findObject(packId, baseId);
            ResolvedGitObjectRead<Base> reader = new ResolvedGitObjectRead<>(storage, index,
                    Optional.of(packId), GitPackObjectResolver::readBase);
            Base base = local.isPresent() ? GitObjectRead.read(storage, local.orElseThrow(), reader)
                    : GitObjectRead.read(storage, index, baseId, reader)
                            .orElseThrow(() -> new IOException("Missing delta base: " + baseId));
            IndexedObject resolved = storage.readPack(packId, object.storedOffset(), object.compressedSize(),
                    (length, input) -> new ContentGitObjectRead<>((type, size, unused, instructions) -> {
                        DeltaByteSource delta = new DeltaByteSource(instructions, base.content());
                        try (BufferedByteInputV2 restored = new BufferedByteInputV2(delta)) {
                            MessageDigest hash = objectHash(base.type(), delta.size());
                            ByteBuffer buffer;
                            while ((buffer = restored.buffer()) != null) {
                                hash.update(buffer);
                            }
                            return new IndexedObject(packId, new ObjectId(hash.digest()), base.type(),
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
                Base base = GitObjectRead.read(storage, index, baseId,
                        new ResolvedGitObjectRead<>(storage, index, GitPackObjectResolver::readBase))
                        .orElseThrow(() -> new IOException("Missing external base: " + baseId));
                appendBase(baseId, base);
            }
        }
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
