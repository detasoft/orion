package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Working pack receiving bytes, index records and resolved objects before publication.
 * Writes and truncation invalidate the pack identity; ingestion assigns a verified trailer identity.
 * The pack owns temporary dependency state. Finish validates dependencies, writes a new trailer when bytes
 * have changed, and releases that state. It does not freeze the pack; later mutations are tracked again.
 * Close releases resources, while discard also removes a mutable working pack. Published or closed packs
 * reject mutations even if a caller retained a mutable reference after transferring ownership to storage.
 */
public interface MutableIndexedPack extends IndexedPack {
    void append(ByteBuffer source) throws IOException;

    void write(long offset, ByteBuffer source) throws IOException;

    void truncate(long size) throws IOException;

    boolean addEntry(PackEntry entry) throws IOException;

    boolean addObject(long offset, ObjectId id, GitObjectType type, long size)
            throws IOException;

    void discard() throws IOException;

    void flush() throws IOException;

    void setId(PackId id) throws IOException;

    PackId finish(long dataEnd) throws IOException;

    void requireMutable() throws IOException;

    Optional<ObjectId> nextExternalBase() throws IOException;

    Optional<PackEntry> waitingFor(ObjectId id, long offset) throws IOException;

    boolean hasUnresolved() throws IOException;
}
