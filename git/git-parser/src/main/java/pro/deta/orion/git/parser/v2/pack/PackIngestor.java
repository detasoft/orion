package pro.deta.orion.git.parser.v2.pack;

import java.io.IOException;

/**
 * Resolves pack objects for PushCommand using an ordinary loop over upload.enumerator().next().
 * This is a consumer of storage and parsing, not part of either API or a required callback implementation.
 * Enumeration automatically preserves original bytes in the upload's sink. Full payloads are streamed into
 * hashing; resolved objects are recorded through upload.addObject with their logical type and content size.
 * Delta resolution and pending dependencies belong here. Retaining or rereading earlier bases is a separate
 * ingestion concern; PackUpload does not expose positional reads or a base lookup API.
 * Missing indexed IDs do not prove external dependencies because later entries may resolve to those IDs.
 * After enumeration, finish pending work or fail, then record confirmed dependencies via addExternalBaseId.
 *
 * <p>Preliminary methods: resolvePack(upload) drives enumeration and resolution; close() releases owned base
 * reads and resolution resources. PushCommand owns this ingestor, performs policy checks, and commits or
 * rolls back the upload. This consumer borrows the enumerator and never closes it or the source input.
 * The parser has no storage dependency and never calls back into this class. Method bodies are placeholders.
 */
public final class PackIngestor implements AutoCloseable {
    public void resolvePack(PackUpload upload) throws IOException {
        throw new UnsupportedOperationException("Pack resolution is not implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Pack resolution cleanup is not implemented");
    }
}
