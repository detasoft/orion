package pro.deta.orion.git.s3;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * S3 index owner. Pending pack entries are shared across accesses until immutable manifest publication.
 * Only published entries survive reopening the owner; bytes, manifests and refs have independent durability.
 */
final class S3GitIndexApi implements GitIndexApi {
    final S3RepositoryObjects objects;
    private final Map<PackId, Pending> pending = new HashMap<>();
    private boolean closed;
    private final Set<GitIndexAccess> accesses = new HashSet<>();

    S3GitIndexApi(S3RepositoryObjects objects) {
        this.objects = objects;
    }

    @Override
    public GitHashAlgorithm hashAlgorithm() { return GitHashAlgorithm.SHA1; }

    @Override
    public GitIndexAccess createAccess(Set<RefId> refs) throws IOException {
        return createAccess(refs, Optional.empty());
    }

    @Override
    public GitIndexAccess createAccess(Set<RefId> refs, Optional<PackId> packId) throws IOException {
        Set<RefId> names = Set.copyOf(refs);
        for (RefId ref : names) ref.requireFullName();
        return open(names, List.of(), packId);
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
            validate(update);
            if (!names.add(update.ref())) throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
        }
        return open(names, updates, packId);
    }

    private GitIndexAccess open(Set<RefId> names, List<RefUpdate> updates, Optional<PackId> packId)
            throws IOException {
        synchronized (this) {
            if (closed) throw new ClosedChannelException();
        }
        S3GitIndex access = new S3GitIndex(this, names, updates, Objects.requireNonNull(packId, "packId"));
        synchronized (this) {
            if (closed) throw new ClosedChannelException();
            accesses.add(access);
            return access;
        }
    }

    synchronized void release(GitIndexAccess access) {
        accesses.remove(access);
        if (closed && accesses.isEmpty()) pending.clear();
    }

    @Override
    public synchronized Set<GitIndexAccess> activeAccesses() {
        return Set.copyOf(accesses);
    }

    static void validate(RefUpdate update) {
        update.ref().requireFullName();
        update.expectedOld().ifPresent(id -> GitHashAlgorithm.SHA1.requireLength(id.byteLength()));
        update.newId().ifPresent(id -> GitHashAlgorithm.SHA1.requireLength(id.byteLength()));
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (accesses.isEmpty()) pending.clear();
    }

    synchronized boolean hasPending(PackId id) { return pending.containsKey(id); }

    synchronized Optional<List<IndexedObject>> pending(PackId id) {
        Pending pack = pending.get(id);
        return pack == null ? Optional.empty() : Optional.of(List.copyOf(pack.entries.values()));
    }

    synchronized Optional<IndexedObject> findPending(PackId packId, ObjectId objectId) {
        Pending pack = pending.get(packId);
        return pack == null ? Optional.empty() : Optional.ofNullable(pack.objects.get(objectId));
    }

    synchronized void add(IndexedObject object) throws IOException {
        Pending pack = pending.computeIfAbsent(object.packId(), ignored -> new Pending());
        IndexedObject previous = pack.entries.putIfAbsent(object.packOffset(), object);
        if (previous != null && !previous.equals(object)) {
            throw new IOException("Cannot change an indexed position");
        }
        pack.objects.merge(object.objectId(), object,
                (left, right) -> left.packOffset() <= right.packOffset() ? left : right);
    }

    synchronized void published(PackId id) { pending.remove(id); }

    private static final class Pending {
        private final NavigableMap<Long, IndexedObject> entries = new TreeMap<>();
        private final Map<ObjectId, IndexedObject> objects = new HashMap<>();
    }

    record Manifest(PackMetadata pack, List<IndexedObject> entries) {}
    record Refs(RefsSnapshot snapshot, String etag) {}

    Optional<Manifest> manifest(PackId id) throws IOException {
        return objects.read(key(id), (stream, length, etag) -> {
            try {
                DataInputStream input = new DataInputStream(stream);
                if (input.readInt() != 0x4f524931) throw new IOException("Invalid S3 pack index format");
                PackId storedId = new PackId(input.readUTF());
                if (!id.equals(storedId)) throw new IOException("S3 pack index belongs to another pack");
                PackChecksum checksum = new PackChecksum(input.readUTF());
                hashAlgorithm().requireLength(checksum.byteLength());
                PackMetadata pack = new PackMetadata(id, checksum, input.readUTF(), input.readLong(), input.readLong());
                int count = input.readInt();
                if (count < 0 || count > length / 40) throw new IOException("Invalid S3 pack index count");
                List<IndexedObject> entries = new ArrayList<>();
                long previous = -1;
                for (int index = 0; index < count; index++) {
                    ObjectId objectId = new ObjectId(input.readUTF());
                    hashAlgorithm().requireLength(objectId.byteLength());
                    GitObjectType type = GitObjectType.valueOf(input.readUTF());
                    long size = input.readLong();
                    long offset = input.readLong();
                    long compressed = input.readLong();
                    Optional<IndexedObject.Delta> delta = Optional.empty();
                    if (input.readBoolean()) {
                        ObjectId base = new ObjectId(input.readUTF());
                        hashAlgorithm().requireLength(base.byteLength());
                        delta = Optional.of(new IndexedObject.Delta(base, input.readLong()));
                    }
                    if (offset <= previous) throw new IOException("Invalid S3 pack index order");
                    previous = offset;
                    entries.add(new IndexedObject(id, objectId, type, size, offset, compressed, delta));
                }
                if (input.read() != -1) throw new IOException("Trailing S3 pack index content");
                pack.validateObjects(entries);
                return new Manifest(pack, List.copyOf(entries));
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid S3 pack index", failure);
            }
        });
    }

    static String key(PackId id) { return "indexes/" + id + ".index"; }

    static byte[] encode(Manifest manifest) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            PackMetadata pack = manifest.pack();
            output.writeInt(0x4f524931);
            output.writeUTF(pack.packId().toString());
            output.writeUTF(pack.packChecksum().toHex());
            output.writeUTF(pack.storageLocation());
            output.writeLong(pack.objectCount());
            output.writeLong(pack.packSize());
            output.writeInt(manifest.entries().size());
            for (IndexedObject object : manifest.entries()) {
                output.writeUTF(object.objectId().toHex());
                output.writeUTF(object.type().name());
                output.writeLong(object.objectSize());
                output.writeLong(object.packOffset());
                output.writeLong(object.compressedSize());
                output.writeBoolean(object.delta().isPresent());
                if (object.delta().isPresent()) {
                    output.writeUTF(object.delta().orElseThrow().baseId().toHex());
                    output.writeLong(object.delta().orElseThrow().instructionSize());
                }
            }
        }
        return bytes.toByteArray();
    }

    Refs refs() throws IOException {
        return objects.read("refs", (stream, length, etag) -> {
            try {
                DataInputStream input = new DataInputStream(stream);
                if (input.readInt() != 0x4f525231) throw new IOException("Invalid S3 refs format");
                Head head;
                if (input.readBoolean()) {
                    RefId target = new RefId(input.readUTF());
                    target.requireFullName();
                    head = new Head.Symbolic(target);
                } else {
                    CommitId target = new CommitId(input.readUTF());
                    hashAlgorithm().requireLength(target.byteLength());
                    head = new Head.Detached(target);
                }
                int count = input.readInt();
                if (count < 0 || count > length / 40) throw new IOException("Invalid S3 ref count");
                Map<RefId, ObjectId> refs = new LinkedHashMap<>();
                for (int index = 0; index < count; index++) {
                    RefId ref = new RefId(input.readUTF());
                    ref.requireFullName();
                    ObjectId id = new ObjectId(input.readUTF());
                    hashAlgorithm().requireLength(id.byteLength());
                    if (refs.putIfAbsent(ref, id) != null) throw new IOException("Duplicate S3 ref");
                }
                if (input.read() != -1) throw new IOException("Trailing S3 refs content");
                return new Refs(new RefsSnapshot(refs, head), etag);
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid S3 refs", failure);
            }
        }).orElseGet(() -> new Refs(new RefsSnapshot(Map.of(),
                new Head.Symbolic(new RefId("refs/heads/main"))), null));
    }

    static byte[] encode(RefsSnapshot refs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4f525231);
            output.writeBoolean(refs.head() instanceof Head.Symbolic);
            output.writeUTF(switch (refs.head()) {
                case Head.Symbolic symbolic -> symbolic.target().value();
                case Head.Detached detached -> detached.target().toHex();
            });
            output.writeInt(refs.refs().size());
            for (Map.Entry<RefId, ObjectId> ref : refs.refs().entrySet()) {
                output.writeUTF(ref.getKey().value());
                output.writeUTF(ref.getValue().toHex());
            }
        }
        return bytes.toByteArray();
    }
}
