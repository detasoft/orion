package pro.deta.orion.config;

import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.util.function.UnaryOperator;

/**
 * A caller-owned configuration candidate. Only apply persists it; closing discards unapplied changes.
 * Apply validates and saves once, then synchronously reloads active consumers. A reload failure can be
 * reported after persistence has succeeded; the consumed edit cannot be applied again.
 */
public interface OrionConfigurationEdit extends AutoCloseable {
    OrionDocument document();

    OrionConfigurationEdit update(UnaryOperator<OrionDocument> update);

    OrionDesiredState.Snapshot apply(String message, UserEmail author);

    @Override
    void close();
}
