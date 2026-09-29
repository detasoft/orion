package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.IOException;
import java.util.Optional;

/** Caller-owned temporary dependency state; finish validates completion and releases this state, not the pack. */
public interface PackUploadIndex extends AutoCloseable {
    Optional<ObjectId> nextExternalBase() throws IOException;

    void finish() throws IOException;

    void addEntry(IndexedPack.EntryMetadata entry) throws IOException;

    void addObject(IndexedPack.EntryMetadata entry, ObjectId id, GitObjectType type, long size)
            throws IOException;

    Optional<IndexedPack.EntryMetadata> waitingFor(ObjectId id, long offset) throws IOException;

    boolean hasUnresolved() throws IOException;

    void close() throws IOException;
}
