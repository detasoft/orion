package pro.deta.orion.git.parser.v2.index.local;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.StringDataType;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.storage.shared.GitLock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

/**
 * Persistent repository index. Connections share one open store per canonical repository path.
 * Short operations share the repository lock; verified entries stay private and buffered until publication.
 * Publication, ref updates and the last connection close commit and sync the store. Secondary maps contain lookup keys.
 */
public final class LocalGitIndex implements GitIndexApi {
    private static final RefId HEAD = new RefId("HEAD");
    private static final String SYMBOLIC = "ref: ";
    private static final Set<String> MAPS = Set.of("refs", "settings", "objects", "locations", "packs", "checksums");
    private static final ConcurrentMap<Path, SharedStore> STORES = new ConcurrentHashMap<>();
    private final Path path;
    private final GitLock lock;
    private final GitHashAlgorithm hashAlgorithm;

    public LocalGitIndex(Path repository) throws IOException {
        this(repository, Optional.empty());
    }

    public LocalGitIndex(Path repository, GitHashAlgorithm hashAlgorithm) throws IOException {
        this(repository, Optional.of(hashAlgorithm));
    }

    private LocalGitIndex(Path repository, Optional<GitHashAlgorithm> requestedAlgorithm) throws IOException {
        Path root = repository.toRealPath();
        path = root.resolve("refs.mv");
        lock = new GitLock(root);
        try (GitLock.Lease lease = lockIndex()) {
            SharedStore existing = STORES.get(path);
            if (existing != null) {
                validateFormat(existing.store);
                hashAlgorithm = readHashAlgorithm(existing.store);
                requireAlgorithm(requestedAlgorithm, hashAlgorithm);

            } else {
                boolean create = Files.notExists(path);
                if (!create) {
                    try (MVStore previous = open(true)) {
                        validateFormat(previous);
                        requireAlgorithm(requestedAlgorithm, readHashAlgorithm(previous));
                    }
                }
                MVStore store = open(false);
                try {
                    if (create) {
                        store.setStoreVersion(3);
                        for (String name : MAPS) {
                            map(store, name);
                        }
                        map(store, "settings").put("objectFormat",
                                requestedAlgorithm.orElse(GitHashAlgorithm.SHA1).wireName());
                        map(store, "refs").put(HEAD.value(), SYMBOLIC + "refs/heads/main");
                        store.commit();
                        store.sync();
                        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                            directory.force(true);
                        }
                    }
                    hashAlgorithm = readHashAlgorithm(store);
                    readHead(map(store, "refs"));
                    store.closeImmediately();
                } catch (IOException | RuntimeException | Error failure) {
                    store.closeImmediately();
                    throw failure;
                }
            }
        } catch (MVStoreException | IllegalArgumentException error) {
            throw storageFailure(error);
        }
    }

    @Override
    public GitHashAlgorithm hashAlgorithm() {
        return hashAlgorithm;
    }

    @Override
    public GitIndexAccess createAccess() throws IOException {
        try (GitLock.Lease lease = lockIndex()) {
            SharedStore shared = STORES.get(path);
            if (shared == null) {
                if (!Files.isRegularFile(path)) {
                    throw new IOException("Repository index file is missing: " + path);
                }
                MVStore store = open(false);
                try {
                    validateFormat(store);
                    if (readHashAlgorithm(store) != hashAlgorithm) {
                        throw new IOException("Repository hash algorithm changed");
                    }
                    shared = new SharedStore(store);
                    STORES.put(path, shared);
                } catch (IOException | RuntimeException | Error failure) {
                    store.closeImmediately();
                    throw failure;
                }
            } else {
                if (shared.store.isClosed()) {
                    throw new IOException("Repository index is closed");
                }
                shared.owners++;
            }
            return new Access(shared);
        } catch (MVStoreException | IllegalArgumentException error) {
            throw storageFailure(error);
        }
    }

    private final class Access implements GitIndexAccess {
        private final SharedStore shared;
        private boolean closed;

        private Access(SharedStore shared) {
            this.shared = shared;
        }

        @Override
        public void addObject(IndexedObject object) throws IOException {
            hashAlgorithm.requireLength(object.objectId().byteLength());
            object.delta().ifPresent(delta -> hashAlgorithm.requireLength(delta.baseId().byteLength()));
            withStore(false, store -> {
                MVMap<String, String> objects = map(store, "objects");
                String key = objectKey(object);
                String value = encode(object);
                String previous = objects.get(key);
                if (value.equals(previous)) {
                    return null;
                }
                if (previous != null || map(store, "packs").containsKey(object.packId().toString())) {
                    throw new IOException("Cannot change an indexed position or add entries to a published pack");
                }
                objects.put(key, value);
                map(store, "locations").put(object.objectId().toHex() + ":" + key, "");
                return null;
            });
        }

        @Override
        public List<IndexedObject> objects(PackId packId) throws IOException {
            Objects.requireNonNull(packId, "packId");
            return withStore(false, store -> LocalGitIndex.objects(store, packId));
        }

        @Override
        public Optional<IndexedObject> findObject(PackId packId, ObjectId objectId) throws IOException {
            Objects.requireNonNull(packId, "packId");
            Objects.requireNonNull(objectId, "objectId");
            return withStore(false, store -> {
                String prefix = objectId.toHex() + ":" + packId + ":";
                Iterator<String> keys = map(store, "locations").keyIterator(prefix);
                if (keys.hasNext()) {
                    String key = keys.next();
                    if (key.startsWith(prefix)) {
                        String objectKey = key.substring(objectId.toHex().length() + 1);
                        return Optional.of(decodeObject(objectKey, map(store, "objects").get(objectKey)));
                    }
                }
                return Optional.empty();
            });
        }

        @Override
        public List<IndexedObject> locations(ObjectId objectId) throws IOException {
            Objects.requireNonNull(objectId, "objectId");
            return withStore(false, store -> {
                String prefix = objectId.toHex() + ":";
                MVMap<String, String> objects = map(store, "objects");
                MVMap<String, String> packs = map(store, "packs");
                List<IndexedObject> result = new ArrayList<>();
                for (String key : keys(map(store, "locations"), prefix)) {
                    String objectKey = key.substring(prefix.length());
                    IndexedObject object = decodeObject(objectKey, objects.get(objectKey));
                    if (packs.containsKey(object.packId().toString())) {
                        result.add(object);
                    }
                }
                return List.copyOf(result);
            });
        }

        @Override
        public Optional<PackMetadata> findPack(PackId packId) throws IOException {
            Objects.requireNonNull(packId, "packId");
            return withStore(false, store -> {
                String value = map(store, "packs").get(packId.toString());
                return value == null ? Optional.empty() : Optional.of(decodePack(packId, value));
            });
        }

        @Override
        public List<PackMetadata> packs(PackChecksum checksum) throws IOException {
            Objects.requireNonNull(checksum, "checksum");
            return withStore(false, store -> {
                String prefix = checksum.toHex() + ":";
                MVMap<String, String> packs = map(store, "packs");
                List<PackMetadata> result = new ArrayList<>();
                for (String key : keys(map(store, "checksums"), prefix)) {
                    PackId id = new PackId(key.substring(prefix.length()));
                    result.add(decodePack(id, packs.get(id.toString())));
                }
                return List.copyOf(result);
            });
        }

        @Override
        public List<PackMetadata> packs() throws IOException {
            return withStore(false, store -> {
                List<PackMetadata> result = new ArrayList<>();
                for (Map.Entry<String, String> entry : map(store, "packs").entrySet()) {
                    result.add(decodePack(new PackId(entry.getKey()), entry.getValue()));
                }
                return List.copyOf(result);
            });
        }

        @Override
        public PackMetadata publishIndex(PackMetadata pack) throws IOException {
            hashAlgorithm.requireLength(pack.packChecksum().byteLength());
            return withStore(true, store -> {
                MVMap<String, String> packs = map(store, "packs");
                String key = pack.packId().toString();
                String previous = packs.get(key);
                if (previous != null) {
                    if (!pack.equals(decodePack(pack.packId(), previous))) {
                        throw new IOException("Cannot change published pack metadata");
                    }
                    return pack;
                }
                pack.validateObjects(LocalGitIndex.objects(store, pack.packId()));
                packs.put(key, encode(pack));
                map(store, "checksums").put(pack.packChecksum().toHex() + ":" + key, "");
                return pack;
            });
        }

        @Override
        public RefsSnapshot snapshotRefs() throws IOException {
            return withStore(false, store -> {
                MVMap<String, String> values = map(store, "refs");
                Head head = readHead(values);
                Map<RefId, ObjectId> refs = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (!entry.getKey().equals(HEAD.value())) {
                        RefId ref = new RefId(entry.getKey());
                        ref.requireFullName();
                        ObjectId id = new ObjectId(entry.getValue());
                        hashAlgorithm.requireLength(id.byteLength());
                        refs.put(ref, id);
                    }
                }
                return new RefsSnapshot(refs, head);
            });
        }

        @Override
        public void updateHead(Head head) throws IOException {
            Objects.requireNonNull(head, "head");
            String value = switch (head) {
                case Head.Symbolic symbolic -> {
                    symbolic.target().requireFullName();
                    yield SYMBOLIC + symbolic.target().value();
                }
                case Head.Detached detached -> {
                    hashAlgorithm.requireLength(detached.target().byteLength());
                    yield detached.target().toHex();
                }
            };
            withStore(true, store -> {
                MVMap<String, String> refs = map(store, "refs");
                readHead(refs);
                refs.put(HEAD.value(), value);
                return null;
            });
        }

        @Override
        public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
            List<RefUpdate> requested = List.copyOf(updates);
            Set<RefId> names = new HashSet<>();
            for (RefUpdate update : requested) {
                update.ref().requireFullName();
                update.expectedOld().ifPresent(id -> hashAlgorithm.requireLength(id.byteLength()));
                update.newId().ifPresent(id -> hashAlgorithm.requireLength(id.byteLength()));
                if (!names.add(update.ref())) {
                    throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
                }
            }
            if (requested.isEmpty()) {
                return List.of();
            }
            try {
                return withStore(true, store -> {
                    MVMap<String, String> refs = map(store, "refs");
                    readHead(refs);
                    List<RefUpdateResult> results = new ArrayList<>(requested.size());
                    boolean failed = false;
                    for (RefUpdate update : requested) {
                        RefUpdateResult.Status status = Objects.equals(refs.get(update.ref().value()),
                                update.expectedOld().map(ObjectId::toHex).orElse(null)) ? APPLIED : EXPECTED_OLD_MISMATCH;
                        results.add(new RefUpdateResult(update, status, Optional.empty()));
                        failed |= status != APPLIED;
                    }
                    for (int position = 0; position < results.size(); position++) {
                        RefUpdateResult result = results.get(position);
                        if (result.status() != APPLIED) {
                            continue;
                        }
                        RefUpdate update = result.update();
                        if (atomic && failed) {
                            results.set(position, new RefUpdateResult(update, ATOMIC_ABORTED, Optional.empty()));
                        } else if (update.newId().isPresent()) {
                            refs.put(update.ref().value(), update.newId().orElseThrow().toHex());
                        } else {
                            refs.remove(update.ref().value());
                        }
                    }
                    return List.copyOf(results);
                });
            } catch (IOException error) {
                List<RefUpdateResult> results = new ArrayList<>(requested.size());
                for (RefUpdate update : requested) {
                    results.add(new RefUpdateResult(update, STORAGE_ERROR, Optional.ofNullable(error.getMessage())));
                }
                return List.copyOf(results);
            }
        }

        @Override
        public void close() throws IOException {
            try (GitLock.Lease lease = lockIndex()) {
                if (closed) {
                    return;
                }
                closed = true;
                if (--shared.owners == 0) {
                    STORES.remove(path, shared);
                    try {
                        shared.store.commit();
                        shared.store.sync();
                    } finally {
                        shared.store.closeImmediately();
                    }
                }
            } catch (MVStoreException | IllegalArgumentException error) {
                throw storageFailure(error);
            }
        }

        private <T> T withStore(boolean durable, Operation<T> operation) throws IOException {
            try (GitLock.Lease lease = lockIndex()) {
                if (closed || shared.store.isClosed()) {
                    throw new IOException("Repository index is closed");
                }
                if (!Files.isRegularFile(path)) {
                    throw new IOException("Repository index file is missing: " + path);
                }
                T result = operation.apply(shared.store);
                if (durable) {
                    try {
                        shared.store.commit();
                        shared.store.sync();
                    } catch (MVStoreException failure) {
                        shared.store.closeImmediately();
                        throw failure;
                    }
                }
                return result;
            } catch (MVStoreException | IllegalArgumentException error) {
                throw storageFailure(error);
            }
        }

    }

    private static void requireAlgorithm(Optional<GitHashAlgorithm> requested, GitHashAlgorithm actual)
            throws IOException {
        if (requested.isPresent() && requested.orElseThrow() != actual) {
            throw new IOException("Repository hash algorithm cannot change after creation");
        }
    }

    private static final class SharedStore {
        private final MVStore store;
        private int owners = 1;

        private SharedStore(MVStore store) {
            this.store = store;
        }
    }

    private MVStore open(boolean readOnly) {
        MVStore.Builder builder = new MVStore.Builder().fileName(path.toString()).cacheSize(1)
                .autoCommitDisabled().autoCommitBufferSize(0);
        if (readOnly) {
            builder.readOnly();
        }
        return builder.open();
    }

    private static MVMap<String, String> map(MVStore store, String name) {
        return store.openMap(name, new MVMap.Builder<String, String>()
                .keyType(StringDataType.INSTANCE).valueType(StringDataType.INSTANCE));
    }

    private static void validateFormat(MVStore store) throws IOException {
        if (store.getStoreVersion() != 3 || !store.getMapNames().equals(MAPS)) {
            throw new IOException("Unsupported repository index format");
        }
    }

    private static GitHashAlgorithm readHashAlgorithm(MVStore store) throws IOException {
        String value = map(store, "settings").get("objectFormat");
        for (GitHashAlgorithm algorithm : GitHashAlgorithm.values()) {
            if (algorithm.wireName().equals(value)) {
                return algorithm;
            }
        }
        throw new IOException("Invalid repository hash algorithm");
    }

    private Head readHead(MVMap<String, String> refs) throws IOException {
        String value = refs.get(HEAD.value());
        if (value == null) {
            throw new IOException("Missing HEAD in repository index");
        }
        if (value.startsWith(SYMBOLIC)) {
            RefId target = new RefId(value.substring(SYMBOLIC.length()));
            target.requireFullName();
            return new Head.Symbolic(target);
        }
        CommitId target = new CommitId(value);
        hashAlgorithm.requireLength(target.byteLength());
        return new Head.Detached(target);
    }

    private static List<String> keys(MVMap<String, String> values, String prefix) {
        List<String> result = new ArrayList<>();
        Iterator<String> keys = values.keyIterator(prefix);
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(prefix)) {
                break;
            }
            result.add(key);
        }
        return result;
    }

    private static List<IndexedObject> objects(MVStore store, PackId packId) throws IOException {
        MVMap<String, String> values = map(store, "objects");
        List<IndexedObject> result = new ArrayList<>();
        for (String key : keys(values, packId + ":")) {
            result.add(decodeObject(key, values.get(key)));
        }
        return List.copyOf(result);
    }

    private static String objectKey(IndexedObject object) {
        String offset = Long.toHexString(object.packOffset());
        return object.packId() + ":" + "0".repeat(16 - offset.length()) + offset;
    }

    private static String encode(IndexedObject object) {
        String value = object.objectId().toHex() + ":" + object.type().name() + ":" + object.objectSize()
                + ":" + object.compressedSize();
        if (object.delta().isPresent()) {
            IndexedObject.Delta delta = object.delta().orElseThrow();
            value += ":" + delta.baseId().toHex() + ":" + delta.instructionSize();
        }
        return value;
    }

    private static IndexedObject decodeObject(String key, String value) throws IOException {
        if (value == null) {
            throw new IOException("Missing indexed object row");
        }
        String[] fields = value.split(":", -1);
        if (fields.length != 4 && fields.length != 6) {
            throw new IOException("Invalid indexed object row");
        }
        int split = key.indexOf(':');
        Optional<IndexedObject.Delta> delta = fields.length == 4 ? Optional.empty()
                : Optional.of(new IndexedObject.Delta(new ObjectId(fields[4]), Long.parseLong(fields[5])));
        return new IndexedObject(new PackId(key.substring(0, split)), new ObjectId(fields[0]),
                GitObjectType.valueOf(fields[1]), Long.parseLong(fields[2]),
                Long.parseLong(key.substring(split + 1), 16), Long.parseLong(fields[3]), delta);
    }

    private static String encode(PackMetadata pack) {
        return pack.packChecksum().toHex() + ":" + pack.objectCount() + ":" + pack.packSize() + ":"
                + Base64.getEncoder().encodeToString(pack.storageLocation().getBytes(StandardCharsets.UTF_8));
    }

    private static PackMetadata decodePack(PackId packId, String value) throws IOException {
        if (value == null) {
            throw new IOException("Missing published pack row");
        }
        String[] fields = value.split(":", -1);
        if (fields.length != 4) {
            throw new IOException("Invalid published pack row");
        }
        String location = new String(Base64.getDecoder().decode(fields[3]), StandardCharsets.UTF_8);
        return new PackMetadata(packId, new PackChecksum(fields[0]), location,
                Long.parseLong(fields[1]), Long.parseLong(fields[2]));
    }

    private GitLock.Lease lockIndex() throws IOException {
        try {
            return lock.lock();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while accessing repository index", error);
        }
    }

    private IOException storageFailure(RuntimeException error) {
        return new IOException("Failed to access repository index " + path, error);
    }

    @FunctionalInterface
    private interface Operation<T> {
        T apply(MVStore store) throws IOException;
    }
}
