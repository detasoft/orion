package pro.deta.orion.config;

import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.util.Result;

import java.util.Objects;

/** A document result and its independent storage revision, including absent or invalid documents. */
public record OrionDocumentRevision(Result<OrionDocument> result, VcRevision revision) {
    public OrionDocumentRevision {
        Objects.requireNonNull(result, "document result");
        Objects.requireNonNull(revision, "revision");
    }
}
