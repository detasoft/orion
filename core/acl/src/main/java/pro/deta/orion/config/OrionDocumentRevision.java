package pro.deta.orion.config;

import pro.deta.orion.schema.orion.v2.OrionDocument;

public record OrionDocumentRevision(OrionDocument document, OrionRevision revision) {
}
