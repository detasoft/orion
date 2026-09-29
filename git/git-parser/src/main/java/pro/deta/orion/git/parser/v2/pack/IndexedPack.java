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
 * Read access to pack bytes and their physical-entry and resolved-object index.
 * A read view does not imply that ingestion or delta resolution is complete; records may still be unresolved.
 * Returned inputs borrow the pack and must be closed before it. Only the resource owner may close the pack;
 * successful storage publication transfers ownership to storage.
 * Offsets iterate in ascending physical order. Missing records and object offsets are null; unresolved
 * records have no object ID or logical type and a size of -1. Entry metadata describes compressed bytes,
 * while a resolved record carries the logical object type and size after delta application.
 */
public interface IndexedPack extends AutoCloseable {
    int read(long offset, ByteBuffer target) throws IOException;

    long size() throws IOException;

    PackId id() throws IOException;

    boolean checksumMatches(PackId expected) throws IOException;

    <R> R readObject(long offset, GitObjectRead<R> reader) throws IOException;

    <R> Optional<R> readObject(ObjectId id, GitObjectRead<R> reader) throws IOException;

    long dataEnd(long offset) throws IOException;

    Optional<ObjectId> baseId(long offset) throws IOException;

    <R> R readObject(EntryMetadata entry, long end, Optional<ObjectId> baseId,
                     GitObjectRead<R> reader) throws IOException;

    Optional<EntryMetadata> find(ObjectId id) throws IOException;

    Optional<EntryMetadata> find(long offset) throws IOException;

    void close() throws IOException;

    long entryCount();

    long objectCount();

    Set<ObjectId> objectIds();

    BufferedByteInputV2 input() throws IOException;

    Iterator<Long> offsets();

    Long objectOffset(ObjectId id);

    Record record(long offset) throws IOException;

    record EntryMetadata(long offset, long dataOffset, long inflatedSize, GitObjectType type,
                         OptionalLong baseOffset, Optional<ObjectId> baseId) {
    }

    record Record(EntryMetadata entry, ObjectId objectId, GitObjectType type, long size) {
    }
}
