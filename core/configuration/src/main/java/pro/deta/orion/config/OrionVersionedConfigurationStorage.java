package pro.deta.orion.config;

import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;

import java.util.function.Consumer;

/** Stores configuration documents together with their repository revision. */
public interface OrionVersionedConfigurationStorage {
    Result<OrionDocumentRevision> load();

    OrionRevision save(OrionRevision expectedRevision, OrionDocument document, ChangeMetadata metadata);

    ChangeSubscription onChange(Consumer<String> listener);

    @FunctionalInterface
    interface ChangeSubscription extends AutoCloseable {
        @Override
        void close();
    }
}
