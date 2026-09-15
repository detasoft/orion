package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.io.IOException;

/**
 * Owns one pack upload: its enumerator, byte sink, accumulated object index, and external dependencies.
 * Distinct instances isolate attempts even when their final PackIds match. enumerator returns the same
 * borrowed parser for the lifetime of the attempt. Its raw-byte sink belongs to this upload and receives
 * bytes directly from the enumerator as parsing progresses, independently of resolved index entries.
 * Sink I/O failures propagate through enumeration as IOException. There is no separate public write method.
 * addObject associates a fully consumed entry with its resolved ID, logical type, and content size.
 * Results may arrive in dependency order; identical repeats are harmless and conflicting results fail.
 * addExternalBaseId accumulates confirmed external dependencies, not every encountered REF_DELTA base.
 * Reconstruction, hashing, and access to bases needed for deferred resolution belong to the caller.
 *
 * <p>commit requires enumeration through the verified checksum, a result for every physical entry, and
 * confirmed external dependencies. Empty packs also require completed enumeration. Storage preserves
 * external bases and durably publishes bytes, index, and manifest under the verified PackId lock.
 * An I/O error can have an uncertain commit outcome; retries inspect the durable manifest.
 * rollback releases the owned parser and sink and discards only this attempt's unpublished staging.
 * It is idempotent and never removes committed data or another attempt's resources. The caller invokes
 * it in finally, including after commit; cleanup must not mask the original operation failure.
 * Neither outcome closes the caller's source input. Recovery ignores incomplete staging.
 * Methods are used sequentially within the owning operation. This is a contract for future implementation.
 */
public interface PackUpload {
    PackEnumerator enumerator();

    void addObject(PackEnumerator.Entry entry, ObjectId objectId, ObjectType type, long size)
            throws IOException;

    void addExternalBaseId(ObjectId externalBase) throws IOException;

    void commit() throws IOException;

    void rollback() throws IOException;
}
