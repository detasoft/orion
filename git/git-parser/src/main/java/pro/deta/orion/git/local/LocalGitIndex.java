package pro.deta.orion.git.local;

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
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;

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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

/**
 * One persistent index owns a repository. Its accesses share one store, opened while accesses are active.
 * Independent ingestions write distinct packs concurrently. Entries stay hidden until publication.
 * Pack publication, apply and the last connection close commit and sync the store. Ref updates stay
 * private until apply atomically compares and updates the shared refs. Secondary maps contain lookup keys.
 * Refs are persisted as one snapshot replaced through MVMap CAS; unrelated ref changes are merged on retry.
 */
public final class LocalGitIndex implements GitIndexApi {
    private static final RefId HEAD = new RefId("HEAD");
    private static final String SYMBOLIC = "ref: ";
    private static final Set<String> MAPS = Set.of("refs", "settings", "objects", "locations", "packs", "checksums");
    private static final String REFS_SNAPSHOT = "snapshot";
    private final Path path;
    private MVStore store;
    private final Set<GitIndexAccess> accesses = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final GitHashAlgorithm hashAlgorithm;

    public LocalGitIndex(Path repository) throws IOException {
        this(repository, Optional.empty());
    }

    public LocalGitIndex(Path repository, GitHashAlgorithm hashAlgorithm) throws IOException {
        this(repository, Optional.of(hashAlgorithm));
    }

    private LocalGitIndex(Path repository, Optional<GitHashAlgorithm> requestedAlgorithm) throws IOException {
        path = repository.toRealPath().resolve("refs.mv");
        MVStore store = acquireStore(true, requestedAlgorithm, null);
        try {
            hashAlgorithm = readHashAlgorithm(store);
            readHead(readRefs(store));
        } finally {
            releaseStore(null);
        }
    }

    private synchronized MVStore acquireStore(boolean create, Optional<GitHashAlgorithm> requested, Access access)
            throws IOException {
        checkInterrupted();
        if (closed) throw new IOException("Repository index is closed");
        if (store != null) {
            if (store.isClosed()) throw new IOException("Repository index is closed");
            if (access != null) accesses.add(access);
            return store;
        }
        boolean missing = Files.notExists(path);
        if ((!create || !missing) && !Files.isRegularFile(path)) {
            throw new IOException("Repository index file is missing: " + path);
        }
        try {
            MVStore opened = open();
            try {
                if (missing) {
                    for (String name : MAPS) map(opened, name);
                    map(opened, "settings").put("objectFormat",
                            requested.orElse(GitHashAlgorithm.SHA1).wireName());
                    map(opened, "refs").put(REFS_SNAPSHOT,
                            encodeRefs(Map.of(HEAD.value(), SYMBOLIC + "refs/heads/main")));
                } else {
                    validateFormat(opened);
                    requireAlgorithm(requested, readHashAlgorithm(opened));
                }
                if (missing) {
                    opened.commit();
                    opened.sync();
                    try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                        directory.force(true);
                    }
                }
                store = opened;
                if (access != null) accesses.add(access);
                return opened;
            } catch (IOException | RuntimeException | Error failure) {
                opened.closeImmediately();
                throw failure;
            }
        } catch (MVStoreException | IllegalArgumentException failure) {
            throw storageFailure(failure);
        }
    }

    private synchronized void releaseStore(Access access) {
        if (access != null) accesses.remove(access);
        if (!accesses.isEmpty()) return;
        MVStore released = store;
        store = null;
        try {
            if (!released.isClosed() && released.hasUnsavedChanges()) {
                released.commit();
                released.sync();
            }
        } finally {
            released.closeImmediately();
        }
    }

    @Override
    public GitHashAlgorithm hashAlgorithm() {
        return hashAlgorithm;
    }

    @Override
    public GitIndexAccess createAccess(Set<RefId> refs) throws IOException {
        return createAccess(refs, Optional.empty());
    }

    @Override
    public GitIndexAccess createAccess(Set<RefId> refs, Optional<PackId> packId) throws IOException {
        Set<RefId> names = Set.copyOf(refs);
        for (RefId ref : names) {
            ref.requireFullName();
        }
        return createAccess(names, List.of(), packId);
    }

    @Override
    public GitIndexAccess createAccess(List<RefUpdate> updates) throws IOException {
        return createAccess(updates, Optional.empty());
    }

    @Override
    public GitIndexAccess createAccess(List<RefUpdate> updates, Optional<PackId> packId) throws IOException {
        updates = List.copyOf(updates);
        Set<RefId> names = new HashSet<>();
        for (RefUpdate update : updates) {
            update.ref().requireFullName();
            update.expectedOld().ifPresent(id -> hashAlgorithm.requireLength(id.byteLength()));
            update.newId().ifPresent(id -> hashAlgorithm.requireLength(id.byteLength()));
            if (!names.add(update.ref())) {
                throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
            }
        }
        return createAccess(names, updates, packId);
    }

    private GitIndexAccess createAccess(Set<RefId> names, List<RefUpdate> updates, Optional<PackId> packId)
            throws IOException {
        Access access = new Access(packId);
        acquireStore(false, Optional.of(hashAlgorithm), access);
        try {
            Map<String, String> refs = readRefs(store);
            for (RefId ref : names) {
                access.originalRefs.put(ref, Optional.ofNullable(refs.get(ref.value())).map(ObjectId::new));
            }
            for (RefUpdate update : updates) {
                Optional<ObjectId> actual = access.originalRefs.get(update.ref());
                if (!actual.equals(update.expectedOld())) throw new GitRefConflictException(update, actual);
                access.changedRefs.put(update.ref(), update.newId());
            }
            return access;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                access.discard();
            } catch (IOException | RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public Set<GitIndexAccess> activeAccesses() {
        return Set.copyOf(accesses);
    }

    /** Runs maintenance only while no other access exists, blocking new accesses until it finishes. */
    public synchronized <T, E extends Exception> Optional<T> whenIdle(GitIndexApi.Operation<T, E> operation)
            throws IOException, E {
        if (!accesses.isEmpty()) return Optional.empty();
        return Optional.of(withAccess(operation));
    }

    private final class Access implements GitIndexAccess {
        private final Optional<PackId> packId;
        private final Map<RefId, Optional<ObjectId>> originalRefs = new LinkedHashMap<>();
        private final Map<RefId, Optional<ObjectId>> changedRefs = new LinkedHashMap<>();
        private String originalHead;
        private String changedHead;
        private boolean closed;

        private Access(Optional<PackId> packId) {
            this.packId = Objects.requireNonNull(packId, "packId");
        }

        @Override
        public Optional<PackId> packId() {
            return packId;
        }

        @Override
        public void addObject(IndexedObject object) throws IOException {
            hashAlgorithm.requireLength(object.objectId().byteLength());
            object.delta().ifPresent(delta -> hashAlgorithm.requireLength(delta.baseId().byteLength()));
            requirePack(object.packId());
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
                    String value = packs.get(id.toString());
                    if (value != null) result.add(decodePack(id, value));
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
            requirePack(pack.packId());
            return withStore(true, store -> {
                pack.validateObjects(LocalGitIndex.objects(store, pack.packId()));
                MVMap<String, String> packs = map(store, "packs");
                String key = pack.packId().toString();
                String previous = packs.get(key);
                if (previous != null) {
                    if (!pack.equals(decodePack(pack.packId(), previous))) {
                        throw new IOException("Cannot change published pack metadata");
                    }
                    return pack;
                }
                map(store, "checksums").put(pack.packChecksum().toHex() + ":" + key, "");
                packs.put(key, encode(pack));
                return pack;
            });
        }

        @Override
        public RefsSnapshot snapshotRefs() throws IOException {
            return withStore(false, store -> {
                Map<String, String> values = readRefs(store);
                Head head = changedHead == null ? readHead(values)
                        : readHead(Map.of(HEAD.value(), changedHead));
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
                overlay(refs, originalRefs);
                overlay(refs, changedRefs);
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
            withStore(false, store -> {
                Map<String, String> refs = readRefs(store);
                readHead(refs);
                if (changedHead == null) {
                    originalHead = refs.get(HEAD.value());
                }
                changedHead = value;
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
                if (!originalRefs.containsKey(update.ref())) {
                    throw new IllegalArgumentException("Ref was not declared when opening access: " + update.ref());
                }
                if (!names.add(update.ref())) {
                    throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
                }
            }
            if (requested.isEmpty()) {
                return List.of();
            }
            try {
                return withStore(false, store -> {
                    Map<String, String> refs = readRefs(store);
                    readHead(refs);
                    List<RefUpdateResult> results = new ArrayList<>(requested.size());
                    boolean failed = false;
                    for (RefUpdate update : requested) {
                        Optional<ObjectId> previous = changedRefs.getOrDefault(update.ref(),
                                originalRefs.get(update.ref()));
                        RefUpdateResult.Status status = previous.equals(update.expectedOld())
                                ? APPLIED : EXPECTED_OLD_MISMATCH;
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
                        } else {
                            if (originalRefs.get(update.ref()).isEmpty() && update.newId().isEmpty()) {
                                changedRefs.remove(update.ref());
                            } else {
                                changedRefs.put(update.ref(), update.newId());
                            }
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
        public void apply() throws IOException {
            try {
                withStore(true, store -> {
                    MVMap<String, String> snapshots = map(store, "refs");
                    for (;;) {
                        checkInterrupted();
                        String previous = snapshots.get(REFS_SNAPSHOT);
                        Map<String, String> refs = decodeRefs(previous);
                        for (Map.Entry<RefId, Optional<ObjectId>> entry : changedRefs.entrySet()) {
                            Optional<ObjectId> actual = Optional.ofNullable(refs.get(entry.getKey().value()))
                                    .map(ObjectId::new);
                            Optional<ObjectId> expected = originalRefs.get(entry.getKey());
                            if (!actual.equals(expected)) {
                                throw new GitRefConflictException(
                                        new RefUpdate(entry.getKey(), expected, entry.getValue()), actual);
                            }
                        }
                        if (changedHead != null && !Objects.equals(refs.get(HEAD.value()), originalHead)) {
                            throw new IOException("Concurrent repository HEAD modification");
                        }
                        for (Map.Entry<RefId, Optional<ObjectId>> entry : changedRefs.entrySet()) {
                            if (entry.getValue().isPresent()) {
                                refs.put(entry.getKey().value(), entry.getValue().orElseThrow().toHex());
                            } else {
                                refs.remove(entry.getKey().value());
                            }
                        }
                        if (changedHead != null) {
                            refs.put(HEAD.value(), changedHead);
                        }
                        if (snapshots.replace(REFS_SNAPSHOT, previous, encodeRefs(refs))) break;
                    }
                    return null;
                });
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    discard();
                } catch (IOException | RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            discard();
        }

        private void overlay(Map<RefId, ObjectId> target, Map<RefId, Optional<ObjectId>> changes) {
            for (Map.Entry<RefId, Optional<ObjectId>> entry : changes.entrySet()) {
                if (entry.getValue().isPresent()) {
                    target.put(entry.getKey(), entry.getValue().orElseThrow());
                } else {
                    target.remove(entry.getKey());
                }
            }
        }

        @Override
        public void discard() throws IOException {
            boolean interrupted = false;
            try {
                interrupted = Thread.interrupted();
                release();
            } catch (MVStoreException | IllegalArgumentException error) {
                throw storageFailure(error);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void release() {
            if (closed) {
                return;
            }
            closed = true;
            changedRefs.clear();
            changedHead = null;
            releaseStore(this);
        }

        private void requirePack(PackId candidate) throws IOException {
            if (!packId.equals(Optional.ofNullable(candidate))) {
                throw new IOException("Access does not own pack: " + candidate);
            }
        }

        private <T> T withStore(boolean durable, Operation<T> operation) throws IOException {
            try {
                checkInterrupted();
                if (closed || store.isClosed()) {
                    throw new IOException("Repository index is closed");
                }
                T result = operation.apply(store);
                if (durable) {
                    try {
                        store.commit();
                        store.sync();
                    } catch (MVStoreException failure) {
                        store.closeImmediately();
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

    private MVStore open() {
        return new MVStore.Builder().fileName(path.toString()).cacheSize(1)
                .autoCommitDisabled().autoCommitBufferSize(0).open();
    }

    private static MVMap<String, String> map(MVStore store, String name) {
        return store.openMap(name, new MVMap.Builder<String, String>()
                .keyType(StringDataType.INSTANCE).valueType(StringDataType.INSTANCE));
    }

    private static void validateFormat(MVStore store) throws IOException {
        if (!store.getMapNames().equals(MAPS)) {
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

    private static Map<String, String> readRefs(MVStore store) throws IOException {
        return decodeRefs(map(store, "refs").get(REFS_SNAPSHOT));
    }

    private static String encodeRefs(Map<String, String> refs) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(refs).entrySet()) {
            result.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
        }
        return result.toString();
    }

    private static Map<String, String> decodeRefs(String snapshot) throws IOException {
        if (snapshot == null) throw new IOException("Missing refs snapshot");
        Map<String, String> refs = new LinkedHashMap<>();
        for (String row : snapshot.split("\n")) {
            int separator = row.indexOf('\t');
            if (separator <= 0 || refs.putIfAbsent(row.substring(0, separator),
                    row.substring(separator + 1)) != null) {
                throw new IOException("Invalid refs snapshot");
            }
        }
        return refs;
    }

    private Head readHead(Map<String, String> refs) throws IOException {
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

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted while accessing repository index");
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
