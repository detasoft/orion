package pro.deta.orion.git.s3;

import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitRefConflictException;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.RefSelection;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

/**
 * One index access borrowing the owner's shared published manifests.
 * One conditional refs object write atomically applies refs and HEAD.
 */
final class S3GitIndex implements GitIndexAccess {
    private final S3GitIndexApi owner;
    private final Optional<PackId> packId;
    private final Map<RefId, Optional<ObjectId>> originalRefs = new LinkedHashMap<>();
    private final Map<RefId, Optional<ObjectId>> changedRefs = new LinkedHashMap<>();
    private Head originalHead;
    private Head changedHead;
    private boolean closed;

    S3GitIndex(S3GitIndexApi owner, Set<RefId> names, List<RefUpdate> updates, Optional<PackId> packId)
            throws IOException {
        this.owner = owner;
        this.packId = packId;
        Map<RefId, ObjectId> refs = owner.refs().snapshot().refs();
        for (RefId name : names) originalRefs.put(name, Optional.ofNullable(refs.get(name)));
        for (RefUpdate update : updates) {
            Optional<ObjectId> actual = originalRefs.get(update.ref());
            if (!actual.equals(update.expectedOld())) throw new GitRefConflictException(update, actual);
            changedRefs.put(update.ref(), update.newId());
        }
    }

    @Override
    public Optional<PackId> packId() { return packId; }

    @Override
    public void addObject(IndexedObject object) throws IOException {
        requireOpen();
        requirePack(object.packId());
        owner.hashAlgorithm().requireLength(object.objectId().byteLength());
        object.delta().ifPresent(delta -> owner.hashAlgorithm().requireLength(delta.baseId().byteLength()));
        if (!owner.hasPending(object.packId())) {
            Optional<S3GitIndexApi.Manifest> published = owner.manifest(object.packId());
            if (published.isPresent()) {
                if (published.orElseThrow().entries().contains(object)) return;
                throw new IOException("Cannot change a published pack index");
            }
        }
        owner.add(object);
    }

    @Override
    public List<IndexedObject> objects(PackId id) throws IOException {
        requireOpen();
        Objects.requireNonNull(id, "packId");
        Optional<List<IndexedObject>> pending = owner.pending(id);
        if (pending.isPresent()) return pending.orElseThrow();
        return owner.manifest(id).map(S3GitIndexApi.Manifest::entries).orElse(List.of());
    }

    @Override
    public Optional<IndexedObject> findObject(PackId pack, ObjectId id) throws IOException {
        requireOpen();
        Objects.requireNonNull(pack, "packId");
        Objects.requireNonNull(id, "objectId");
        Optional<IndexedObject> pending = owner.findPending(pack, id);
        if (pending.isPresent()) return pending;
        if (owner.hasPending(pack)) return Optional.empty();
        return owner.findObject(pack, id);
    }

    @Override
    public List<IndexedObject> locations(ObjectId id) throws IOException {
        Objects.requireNonNull(id, "objectId");
        requireOpen();
        return owner.locations(id);
    }

    @Override
    public Optional<PackMetadata> findPack(PackId id) throws IOException {
        requireOpen();
        return owner.manifest(Objects.requireNonNull(id, "packId")).map(S3GitIndexApi.Manifest::pack);
    }

    @Override
    public List<PackMetadata> packs(PackChecksum checksum) throws IOException {
        requireOpen();
        Objects.requireNonNull(checksum, "checksum");
        List<PackMetadata> packs = new ArrayList<>();
        for (S3GitIndexApi.Manifest manifest : owner.published()) {
            if (manifest.pack().packChecksum().equals(checksum)) packs.add(manifest.pack());
        }
        return List.copyOf(packs);
    }

    @Override
    public List<PackMetadata> packs() throws IOException {
        requireOpen();
        List<PackMetadata> packs = new ArrayList<>();
        for (S3GitIndexApi.Manifest manifest : owner.published()) packs.add(manifest.pack());
        return List.copyOf(packs);
    }

    @Override
    public PackMetadata publishIndex(PackMetadata pack) throws IOException {
        requireOpen();
        requirePack(pack.packId());
        owner.hashAlgorithm().requireLength(pack.packChecksum().byteLength());
        return owner.publish(pack);
    }

    @Override
    public RefsSnapshot snapshotRefs(RefSelection selection) throws IOException {
        Objects.requireNonNull(selection, "selection");
        requireOpen();
        RefsSnapshot current = owner.refs().snapshot();
        Map<RefId, ObjectId> refs = new LinkedHashMap<>(current.refs());
        overlay(refs, originalRefs);
        overlay(refs, changedRefs);
        refs.keySet().removeIf(ref -> !selection.matches(ref));
        return new RefsSnapshot(refs, changedHead == null ? current.head() : changedHead);
    }

    @Override
    public void updateHead(Head head) throws IOException {
        requireOpen();
        Objects.requireNonNull(head, "head");
        if (head instanceof Head.Symbolic symbolic) symbolic.target().requireFullName();
        else if (head instanceof Head.Detached detached) owner.hashAlgorithm().requireLength(detached.target().byteLength());
        if (changedHead == null) originalHead = owner.refs().snapshot().head();
        changedHead = head;
    }

    @Override
    public List<RefUpdateResult> updateRefs(List<RefUpdate> updates, boolean atomic) {
        updates = List.copyOf(updates);
        Set<RefId> names = new HashSet<>();
        for (RefUpdate update : updates) {
            S3GitIndexApi.validate(update);
            if (!originalRefs.containsKey(update.ref())) {
                throw new IllegalArgumentException("Ref was not declared when opening access: " + update.ref());
            }
            if (!names.add(update.ref())) throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
        }
        List<RefUpdateResult> results = new ArrayList<>();
        try {
            requireOpen();
            boolean failed = false;
            for (RefUpdate update : updates) {
                Optional<ObjectId> previous = changedRefs.getOrDefault(update.ref(), originalRefs.get(update.ref()));
                RefUpdateResult.Status status = previous.equals(update.expectedOld()) ? APPLIED : EXPECTED_OLD_MISMATCH;
                results.add(new RefUpdateResult(update, status, Optional.empty()));
                failed |= status != APPLIED;
            }
            for (int position = 0; position < results.size(); position++) {
                RefUpdateResult result = results.get(position);
                if (result.status() != APPLIED) continue;
                RefUpdate update = result.update();
                if (atomic && failed) {
                    results.set(position, new RefUpdateResult(update, ATOMIC_ABORTED, Optional.empty()));
                } else if (originalRefs.get(update.ref()).isEmpty() && update.newId().isEmpty()) {
                    changedRefs.remove(update.ref());
                } else {
                    changedRefs.put(update.ref(), update.newId());
                }
            }
        } catch (IOException failure) {
            for (RefUpdate update : updates) results.add(new RefUpdateResult(update, STORAGE_ERROR,
                    Optional.ofNullable(failure.getMessage())));
        }
        return List.copyOf(results);
    }

    @Override
    public void apply() throws IOException {
        requireOpen();
        try {
            if (changedRefs.isEmpty() && changedHead == null) return;
            for (int attempt = 0; attempt < 32; attempt++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("S3 refs interrupted");
                S3GitIndexApi.Refs current = owner.refs();
                Map<RefId, ObjectId> refs = new LinkedHashMap<>(current.snapshot().refs());
                for (Map.Entry<RefId, Optional<ObjectId>> change : changedRefs.entrySet()) {
                    Optional<ObjectId> actual = Optional.ofNullable(refs.get(change.getKey()));
                    Optional<ObjectId> expected = originalRefs.get(change.getKey());
                    if (!actual.equals(expected)) throw new GitRefConflictException(
                            new RefUpdate(change.getKey(), expected, change.getValue()), actual);
                }
                if (changedHead != null && !current.snapshot().head().equals(originalHead)) {
                    throw new IOException("Concurrent repository HEAD modification");
                }
                overlay(refs, changedRefs);
                RefsSnapshot next = new RefsSnapshot(refs,
                        changedHead == null ? current.snapshot().head() : changedHead);
                if (owner.objects.put("refs", S3GitIndexApi.encode(next), current.etag())) return;
            }
            throw new IOException("S3 refs remained busy during conditional publication");
        } finally {
            discard();
        }
    }

    private static void overlay(Map<RefId, ObjectId> target, Map<RefId, Optional<ObjectId>> changes) {
        for (Map.Entry<RefId, Optional<ObjectId>> change : changes.entrySet()) {
            if (change.getValue().isPresent()) target.put(change.getKey(), change.getValue().orElseThrow());
            else target.remove(change.getKey());
        }
    }

    private void requireOpen() throws ClosedChannelException {
        if (closed) throw new ClosedChannelException();
    }

    private void requirePack(PackId candidate) throws IOException {
        if (packId.isEmpty() || !packId.orElseThrow().equals(candidate)) {
            throw new IOException("Pack does not belong to this index access");
        }
    }

    @Override
    public void discard() {
        if (closed) return;
        closed = true;
        owner.release(this);
        changedRefs.clear();
        changedHead = null;
    }
}
