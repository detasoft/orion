package pro.deta.orion.git.parser.v2.storage.memory;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.MutableIndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackEntry;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.storage.shared.PackByteSource;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.*;

final class MemoryIndexedPack implements MutableIndexedPack {
    private final MemoryPackDataStorage bytes = new MemoryPackDataStorage();
    private final NavigableMap<Long, Record> entries = new TreeMap<>();
    private final Map<ObjectId, Long> objects = new HashMap<>();
    private PackId packId;
    private MemoryPackDependencies dependencies;
    private boolean published;

    public void append(ByteBuffer source) throws IOException {
        write(size(), source);
    }

    public void write(long offset, ByteBuffer source) throws IOException {
        requireMutable();
        packId = null;
        bytes.write(offset, source);
    }

    public int read(long offset, ByteBuffer target) throws IOException {
        return bytes.read(offset, target);
    }

    public long size() throws IOException {
        return bytes.size();
    }

    public void truncate(long size) throws IOException {
        requireMutable();
        packId = null;
        bytes.truncate(size);
    }

    public PackId id() throws IOException {
        requireOpen();
        if (packId == null) {
            throw new IOException("Pack checksum is not calculated");
        }
        return packId;
    }

    public void setId(PackId id) throws IOException {
        requireMutable();
        packId = Objects.requireNonNull(id, "id");
    }

    public PackId finish(long dataEnd) throws IOException {
        requireMutable();
        if (packId == null) {
            byte[] checksum = digest(bytes, dataEnd);
            write(dataEnd, ByteBuffer.wrap(checksum));
            packId = new PackId(checksum);
        }
        dependencies().finish();
        dependencies = null;
        return packId;
    }

    public boolean checksumMatches(PackId expected) throws IOException {
        return checksum(bytes).equals(expected)
                && MessageDigest.isEqual(digest(bytes, size() - 20), expected.toBytes());
    }

    public <R> R readObject(long offset, GitObjectRead<R> reader) throws IOException {
        PackEntry entry = find(offset).orElseThrow(() ->
                new IllegalArgumentException("Unknown pack entry offset: " + offset));
        return readStored(entry, bytes, size(), reader);
    }

    public <R> Optional<R> readObject(ObjectId id, GitObjectRead<R> reader) throws IOException {
        Optional<PackEntry> entry = find(id);
        return entry.isEmpty() ? Optional.empty()
                : Optional.of(readObject(entry.orElseThrow().offset(), reader));
    }

    public <R> R readObject(PackEntry entry, long end, Optional<ObjectId> baseId,
                            GitObjectRead<R> reader) throws IOException {
        requireOpen();
        return readBounded(bytes, entry, end, baseId, reader);
    }

    public long dataEnd(long offset) throws IOException {
        requireOpen();
        Long next = entries.higherKey(offset);
        return next == null ? size() - 20 : next;
    }

    public Optional<ObjectId> baseId(long offset) throws IOException {
        Record record = record(offset);
        if (record == null) {
            throw new IOException("Unknown pack entry offset: " + offset);
        }
        PackEntry entry = record.entry();
        if (entry.type() != GitObjectType.OFS_DELTA) {
            return entry.baseId();
        }
        Record base = record(entry.baseOffset().orElseThrow());
        if (base == null || base.objectId() == null) {
            throw new IOException("Offset delta base has no ObjectId");
        }
        return Optional.of(base.objectId());
    }

    public boolean addEntry(long offset, long packOffset, long inflatedSize, GitObjectType type,
                            OptionalLong baseOffset, Optional<ObjectId> baseId) throws IOException {
        requireMutable();
        PackEntry entry = new PackEntry(offset, packOffset, inflatedSize, type, baseOffset, baseId);
        validateEntry(entry);
        Record previous = entries.get(offset);
        if (previous != null) {
            if (!previous.entry().equals(entry)) {
                throw new IOException("Conflicting physical pack entry");
            }
            return false;
        }
        if (baseOffset.isPresent() && !entries.containsKey(baseOffset.getAsLong())) {
            throw new IOException("Offset delta base is not a registered pack entry");
        }
        entries.put(offset, new Record(entry, null, null, -1));
        if (dependencies != null) {
            dependencies.entryAdded(entry);
        }
        return true;
    }

    public boolean addObject(long offset, ObjectId id, GitObjectType type, long size) throws IOException {
        requireMutable();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Record previous = entries.get(offset);
        if (previous == null) {
            throw new IOException("Object completion requires the registered physical entry");
        }
        validateObject(previous.entry(), type, size);
        if (previous.objectId() != null) {
            if (!id.equals(previous.objectId()) || type != previous.type() || size != previous.size()) {
                throw new IOException("Conflicting pack object completion");
            }
            return false;
        }
        Long existingOffset = objects.get(id);
        if (existingOffset != null) {
            Record existing = entries.get(existingOffset);
            if (existing.type() != type || existing.size() != size) {
                throw new IOException("Conflicting metadata for the same object ID");
            }
        }
        entries.put(offset, new Record(previous.entry(), id, type, size));
        objects.putIfAbsent(id, offset);
        if (dependencies != null) {
            dependencies.objectAdded(previous.entry());
        }
        return true;
    }

    public Optional<PackEntry> find(ObjectId id) throws IOException {
        requireOpen();
        Long offset = objects.get(Objects.requireNonNull(id, "id"));
        return offset == null ? Optional.empty() : find(offset.longValue());
    }

    public Optional<PackEntry> find(long offset) throws IOException {
        Record record = record(offset);
        return record == null ? Optional.empty() : Optional.of(record.entry());
    }

    public Record record(long offset) throws IOException {
        requireOpen();
        return entries.get(offset);
    }

    public long entryCount() {
        return entries.size();
    }

    public long objectCount() {
        return objects.size();
    }

    public Set<ObjectId> objectIds() {
        return Set.copyOf(objects.keySet());
    }

    public Iterator<Long> offsets() {
        return entries.navigableKeySet().iterator();
    }

    public Long objectOffset(ObjectId id) {
        return objects.get(id);
    }

    public BufferedByteInputV2 input() throws IOException {
        return new BufferedByteInputV2(new PackByteSource(bytes, 0, size()));
    }

    public void flush() throws IOException {
        requireMutable();
    }

    public Optional<ObjectId> nextExternalBase() throws IOException {
        return dependencies().nextExternalBase();
    }

    public Optional<PackEntry> waitingFor(ObjectId id, long offset) throws IOException {
        return dependencies().waitingFor(id, offset);
    }

    public boolean hasUnresolved() throws IOException {
        return dependencies().hasUnresolved();
    }

    private MemoryPackDependencies dependencies() throws IOException {
        requireMutable();
        if (dependencies == null) {
            dependencies = new MemoryPackDependencies(this);
        }
        return dependencies;
    }

    public void requireMutable() throws IOException {
        requireOpen();
        if (published) {
            throw new IllegalStateException("Pack is read-only");
        }
    }

    void publish() throws IOException {
        requireMutable();
        published = true;
    }

    void requireOpen() throws ClosedChannelException {
        if (!bytes.isOpen()) {
            throw new ClosedChannelException();
        }
    }

    public void close() {
        if (dependencies != null) {
            dependencies.close();
            dependencies = null;
        }
        bytes.close();
        entries.clear();
        objects.clear();
        packId = null;
    }

    public void discard() {
        close();
    }
}
