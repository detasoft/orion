package pro.deta.orion.config;

import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Modification;

import java.util.Objects;
import java.util.function.Consumer;

public class OrionConfigurationModification implements Modification {
    private final OrionDocument document;
    private final Consumer<OrionDocument> onSave;


    public OrionConfigurationModification(OrionDocument document, Consumer<OrionDocument> onSave) {
        this.document = Objects.requireNonNull(document);
        this.onSave = Objects.requireNonNull(onSave);
    }

    public OrionDocument document() {
        return document;
    }

    @Override
    public void apply() {
        onSave.accept(document());
    }
}
