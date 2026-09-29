package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Pack bytes and their physical-entry and resolved-object index.
 * Writes and truncation invalidate the pack identity; ingestion assigns a verified trailer identity,
 * and completion writes a new trailer when bytes have changed. Index completion is a separate step.
 * Returned inputs borrow the pack and must be closed before it. Temporary upload indexes are caller-owned;
 * closing them releases only resolution state, while close() releases pack resources and discard()
 * also removes a mutable working pack. Storage publication takes ownership of accepted packs.
 * Offsets iterate in ascending physical order. Missing records and object offsets are null; unresolved
 * records have no object ID or logical type and a size of -1. Entry metadata describes compressed bytes,
 * while a resolved record carries the logical object type and size after delta application.
 */
public interface IndexedPack extends AutoCloseable {
    void append(ByteBuffer source) throws IOException;

    void write(long offset, ByteBuffer source) throws IOException;

    int read(long offset, ByteBuffer target) throws IOException;

    long size() throws IOException;

    void truncate(long size) throws IOException;

    PackId id() throws IOException;

    boolean checksumMatches(PackId expected) throws IOException;

    <R> R readObject(long offset, GitObjectRead<R> reader) throws IOException;

    <R> Optional<R> readObject(ObjectId id, GitObjectRead<R> reader) throws IOException;

    long dataEnd(long offset) throws IOException;

    Optional<ObjectId> baseId(long offset) throws IOException;

    <R> R readObject(EntryMetadata entry, long end, Optional<ObjectId> baseId,
                     GitObjectRead<R> reader) throws IOException;

    boolean addEntry(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                     OptionalLong baseOffset, Optional<ObjectId> baseId) throws IOException;

    boolean addObject(long offset, ObjectId id, GitObjectType type, long size)
            throws IOException;

    Optional<EntryMetadata> find(ObjectId id) throws IOException;

    Optional<EntryMetadata> find(long offset) throws IOException;

    void close() throws IOException;

    void discard() throws IOException;

    long entryCount();

    long objectCount();

    Set<ObjectId> objectIds();

    BufferedByteInputV2 input() throws IOException;

    void flush() throws IOException;

    void setId(PackId id) throws IOException;

    PackId finish(long dataEnd) throws IOException;

    Iterator<Long> offsets();

    Long objectOffset(ObjectId id);

    Record record(long offset) throws IOException;

    void requireMutable() throws IOException;

    PackUploadIndex newUploadIndex() throws IOException;

    record EntryMetadata(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                         OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }

    record Record(EntryMetadata entry, ObjectId objectId, GitObjectType type, long size) {
    }
}
