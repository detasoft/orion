package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;

import java.io.IOException;
import java.util.Optional;

/**
 * Reads Git objects internally behind GitStorageApi independently of their loose or packed representation.
 * Callers outside this package access these operations only through GitStorageApi.
 * Provides the object view used by graph validation, file loading, and outgoing pack construction.
 * Loose-object registration accepts object ID, logical type, and uncompressed content size only after verified
 * bytes are durably stored by ObjectId.
 * Registration does not ingest bytes, has no pack offset or upload identity, and preserves other locations
 * of the same object. Repeated identical registration is harmless; conflicting metadata is rejected.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code addEntry(objectId, type, size)} - register an already stored loose object through the facade.</li>
 *   <li>{@code read(objectId, reader)} - invoke GitObjectRead with a bounded stored payload and return its
 *       result, or absence without invoking the reader; I/O failures remain errors.</li>
 *   <li>{@code readPrefix(objectId, maxDataBytes)} - return type, full size, and a bounded content prefix.</li>
 * </ul>
 * Method names and signatures are provisional. This view includes objects published through GitPackStorage;
 * callers do not select a backing pack. Physical delta payloads still require resolver reconstruction;
 * read supplies the REF_DELTA base ObjectId alongside the bounded zlib payload as defined by GitObjectRead.
 * Backend implementations own source bounds, final validation, and cleanup; absence never invokes the reader.
 */
class GitObjectStorage {
    <R> Optional<R> read(ObjectId objectId, GitObjectRead<R> reader) throws IOException {
        throw new UnsupportedOperationException("Object reads are not implemented");
    }
}
