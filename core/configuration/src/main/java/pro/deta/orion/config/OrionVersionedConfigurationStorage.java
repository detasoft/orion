package pro.deta.orion.config;

import pro.deta.orion.util.Subscription;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;

import java.util.function.Consumer;

/** Stores configuration documents together with their repository revision. */
public interface OrionVersionedConfigurationStorage {
    Result<OrionDocumentRevision> load();

    Result<OrionDocumentRevision> load(OrionRevision.Stored revision);

    OrionRevision save(OrionRevision expectedRevision, OrionDocument document, ChangeMetadata metadata);

    Subscription onChange(Consumer<OrionDocumentRevision> listener);
}
