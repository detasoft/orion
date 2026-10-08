package pro.deta.orion.config;

import pro.deta.orion.internal.UserEmailMessage;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;

import java.util.function.Consumer;

/** Stores configuration documents together with their repository revision. */
public interface OrionVersionedConfigurationStorage {
    Result<OrionDocumentRevision> load();

    void save(OrionDocumentRevision original, OrionDocument updated, UserEmailMessage author);

    ChangeSubscription onChange(Consumer<String> listener);

    @FunctionalInterface
    interface ChangeSubscription extends AutoCloseable {
        @Override
        void close();
    }
}
