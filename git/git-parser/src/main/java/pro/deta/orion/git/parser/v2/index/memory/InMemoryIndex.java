package pro.deta.orion.git.parser.v2.index.memory;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
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
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

/** In-memory repository index; pending objects become visible together when their pack is published. */
public final class InMemoryIndex implements GitIndexApi {
    private boolean closed;
    private final GitHashAlgorithm hashAlgorithm;
    private final Map<PackId, NavigableMap<Long, IndexedObject>> objects = new LinkedHashMap<>();
    private final Map<ObjectId, List<IndexedObject>> locations = new LinkedHashMap<>();
    private final Map<PackId, PackMetadata> packs = new LinkedHashMap<>();
    private final Map<PackChecksum, List<PackMetadata>> checksums = new LinkedHashMap<>();
    private final Map<RefId, ObjectId> refs = new LinkedHashMap<>();
    private Head head = new Head.Symbolic(new RefId("refs/heads/main"));

    public InMemoryIndex() {
        this(GitHashAlgorithm.SHA1);
    }

    public InMemoryIndex(GitHashAlgorithm hashAlgorithm) {
        this.hashAlgorithm = Objects.requireNonNull(hashAlgorithm, "hashAlgorithm");
    }

    @Override
    public GitHashAlgorithm hashAlgorithm() {
        return hashAlgorithm;
    }

    @Override
    public GitIndexAccess createAccess() {
        return createAccess(Set.of());
    }

    @Override
    public GitIndexAccess createAccess(Set<RefId> requested) {
        Set<RefId> names = Set.copyOf(requested);
        for (RefId ref : names) {
            ref.requireFullName();
        }
        synchronized (this) {
            requireFactoryOpen();
            return new Access(names);
        }
    }

    @Override
    public GitIndexAccess createAccess(List<RefUpdate> updates) throws IOException {
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
        synchronized (this) {
            requireFactoryOpen();
            for (RefUpdate update : updates) {
                Optional<ObjectId> actual = Optional.ofNullable(refs.get(update.ref()));
                if (!actual.equals(update.expectedOld())) {
                    throw new GitRefConflictException(update, actual);
                }
            }
            Access access = new Access(names);
            for (RefUpdate update : updates) {
                access.changedRefs.put(update.ref(), update.newId());
            }
            return access;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private void requireFactoryOpen() {
        if (closed) throw new IllegalStateException("Repository index is closed");
    }

    private final class Access implements GitIndexAccess {
        private final Map<RefId, Optional<ObjectId>> originalRefs = new LinkedHashMap<>();
        private final Map<RefId, Optional<ObjectId>> changedRefs = new LinkedHashMap<>();
        private Head originalHead;
        private Head changedHead;
        private boolean closed;

        private Access(Set<RefId> names) {
            for (RefId ref : names) {
                originalRefs.put(ref, Optional.ofNullable(refs.get(ref)));
            }
        }

        @Override
        public void addObject(IndexedObject object) throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                hashAlgorithm.requireLength(object.objectId().byteLength());
                object.delta().ifPresent(delta -> hashAlgorithm.requireLength(delta.baseId().byteLength()));
                NavigableMap<Long, IndexedObject> entries = objects.get(object.packId());
                IndexedObject previous = entries == null ? null : entries.get(object.packOffset());
                if (object.equals(previous)) {
                    return;
                }
                if (previous != null || packs.containsKey(object.packId())) {
                    throw new IOException("Cannot change an indexed position or add entries to a published pack");
                }
                objects.computeIfAbsent(object.packId(), ignored -> new TreeMap<>()).put(object.packOffset(), object);
                locations.computeIfAbsent(object.objectId(), ignored -> new ArrayList<>()).add(object);
            }
        }

        @Override
        public List<IndexedObject> objects(PackId packId) throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                NavigableMap<Long, IndexedObject> entries = objects.get(Objects.requireNonNull(packId, "packId"));
                return entries == null ? List.of() : List.copyOf(entries.values());
            }
        }

        @Override
        public Optional<IndexedObject> findObject(PackId packId, ObjectId objectId)
                throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                Objects.requireNonNull(packId, "packId");
                Objects.requireNonNull(objectId, "objectId");
                IndexedObject first = null;
                for (IndexedObject entry : locations.getOrDefault(objectId, List.of())) {
                    if (packId.equals(entry.packId()) && (first == null || entry.packOffset() < first.packOffset())) {
                        first = entry;
                    }
                }
                return Optional.ofNullable(first);
            }
        }

        @Override
        public List<IndexedObject> locations(ObjectId objectId) throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                Objects.requireNonNull(objectId, "objectId");
                List<IndexedObject> result = new ArrayList<>();
                for (IndexedObject object : locations.getOrDefault(objectId, List.of())) {
                    if (packs.containsKey(object.packId())) {
                        result.add(object);
                    }
                }
                return List.copyOf(result);
            }
        }

        @Override
        public Optional<PackMetadata> findPack(PackId packId) throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                return Optional.ofNullable(packs.get(Objects.requireNonNull(packId, "packId")));
            }
        }

        @Override
        public List<PackMetadata> packs(PackChecksum checksum) throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                return List.copyOf(checksums.getOrDefault(Objects.requireNonNull(checksum, "checksum"), List.of()));
            }
        }

        @Override
        public List<PackMetadata> packs() throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                return List.copyOf(packs.values());
            }
        }

        @Override
        public PackMetadata publishIndex(PackMetadata pack) throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                hashAlgorithm.requireLength(pack.packChecksum().byteLength());
                PackMetadata previous = packs.get(pack.packId());
                if (previous != null) {
                    if (!previous.equals(pack)) {
                        throw new IOException("Cannot change published pack metadata");
                    }
                    return previous;
                }
                pack.validateObjects(objects(pack.packId()));
                packs.put(pack.packId(), pack);
                checksums.computeIfAbsent(pack.packChecksum(), ignored -> new ArrayList<>()).add(pack);
                return pack;
            }
        }

        public RefsSnapshot snapshotRefs() throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                Map<RefId, ObjectId> snapshot = new LinkedHashMap<>(refs);
                overlay(snapshot, originalRefs);
                overlay(snapshot, changedRefs);
                return new RefsSnapshot(snapshot, changedHead == null ? head : changedHead);
            }
        }

        public void updateHead(Head value) throws IOException {
            Objects.requireNonNull(value, "head");
            if (value instanceof Head.Symbolic symbolic) {
                symbolic.target().requireFullName();
            } else if (value instanceof Head.Detached detached) {
                hashAlgorithm.requireLength(detached.target().byteLength());
            }
            synchronized (InMemoryIndex.this) {
                requireOpen();
                if (changedHead == null) {
                    originalHead = head;
                }
                changedHead = value;
            }
        }

        public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
            updates = List.copyOf(updates);
            Set<RefId> names = new HashSet<>();
            for (RefUpdate update : updates) {
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
            try {
                List<RefUpdateResult> results = new ArrayList<>(updates.size());
                synchronized (InMemoryIndex.this) {
                    requireOpen();
                    boolean failed = false;
                    for (RefUpdate update : updates) {
                        Optional<ObjectId> previous = changedRefs.getOrDefault(update.ref(),
                                originalRefs.get(update.ref()));
                        RefUpdateResult.Status status = previous.equals(update.expectedOld())
                                ? APPLIED : EXPECTED_OLD_MISMATCH;
                        results.add(new RefUpdateResult(update, status, Optional.empty()));
                        failed |= status != APPLIED;
                    }
                    for (int index = 0; index < results.size(); index++) {
                        RefUpdateResult result = results.get(index);
                        if (result.status() != APPLIED) {
                            continue;
                        }
                        RefUpdate update = result.update();
                        if (atomic && failed) {
                            results.set(index, new RefUpdateResult(update, ATOMIC_ABORTED, Optional.empty()));
                        } else {
                            if (originalRefs.get(update.ref()).isEmpty() && update.newId().isEmpty()) {
                                changedRefs.remove(update.ref());
                            } else {
                                changedRefs.put(update.ref(), update.newId());
                            }
                        }
                    }
                }
                return List.copyOf(results);
            } catch (IOException error) {
                List<RefUpdateResult> results = new ArrayList<>(updates.size());
                for (RefUpdate update : updates) {
                    results.add(new RefUpdateResult(update, STORAGE_ERROR, Optional.ofNullable(error.getMessage())));
                }
                return List.copyOf(results);
            }
        }

        @Override
        public void apply() throws IOException {
            synchronized (InMemoryIndex.this) {
                requireOpen();
                try {
                    for (Map.Entry<RefId, Optional<ObjectId>> entry : changedRefs.entrySet()) {
                        Optional<ObjectId> actual = Optional.ofNullable(refs.get(entry.getKey()));
                        Optional<ObjectId> expected = originalRefs.get(entry.getKey());
                        if (!actual.equals(expected)) {
                            throw new GitRefConflictException(
                                    new RefUpdate(entry.getKey(), expected, entry.getValue()), actual);
                        }
                    }
                    if (changedHead != null && !head.equals(originalHead)) {
                        throw new IOException("Concurrent repository HEAD modification");
                    }
                    overlay(refs, changedRefs);
                    if (changedHead != null) {
                        head = changedHead;
                    }
                } finally {
                    discard();
                }
            }
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

        private void requireOpen() throws ClosedChannelException {
            if (closed) {
                throw new ClosedChannelException();
            }
        }

        @Override
        public void discard() {
            synchronized (InMemoryIndex.this) {
                closed = true;
                changedRefs.clear();
                changedHead = null;
            }
        }
    }
}
