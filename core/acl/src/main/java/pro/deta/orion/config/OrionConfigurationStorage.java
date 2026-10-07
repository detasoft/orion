package pro.deta.orion.config;

import pro.deta.orion.internal.UserEmailMessage;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;
import pro.deta.orion.internal.UserEmail;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public interface OrionConfigurationStorage {
    Result<OrionDocumentRevision> load();

    void save(OrionDocumentRevision original,OrionDocument updated, UserEmailMessage author);

    ChangeSubscription onChange(Consumer<String> listener);

    @FunctionalInterface
    interface ChangeSubscription extends AutoCloseable {
        @Override
        void close();
    }
}
