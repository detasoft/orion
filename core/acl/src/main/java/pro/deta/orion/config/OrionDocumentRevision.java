package pro.deta.orion.config;

import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.util.Optional;

public record OrionDocumentRevision(OrionDocument document, Optional<String> revision) {
}
