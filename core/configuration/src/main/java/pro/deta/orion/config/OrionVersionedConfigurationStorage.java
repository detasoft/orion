package pro.deta.orion.config;

import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;

/** Stores configuration documents together with their repository revision. */
public interface OrionVersionedConfigurationStorage {
    Result<OrionDocumentRevision> load();

    Result<OrionDocumentRevision> load(OrionRevision.Stored revision);

    OrionRevision save(OrionRevision expectedRevision, OrionDocument document, ChangeMetadata metadata);
}
