package pro.deta.orion.config;

import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Modification;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Owns a configuration candidate and completes it through a single persistence callback. */
public class OrionConfigurationModification implements Modification {
    private final Function<OrionDocument, OrionRevision.Stored> onSave;
    private OrionDocument document;
    private OrionRevision revision;

    public OrionConfigurationModification(OrionDocument document, OrionRevision revision,
            Function<OrionDocument, OrionRevision.Stored> onSave) {
        this.document = Objects.requireNonNull(document, "document");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.onSave = Objects.requireNonNull(onSave, "onSave");
    }

    public synchronized OrionDocument document() {
        requireOpen();
        return document;
    }

    public synchronized OrionRevision revision() {
        return revision;
    }

    public synchronized OrionDocument modify(OrionDocument document) {
        requireOpen();
        this.document = Objects.requireNonNull(document, "document");
        return document;
    }

    public synchronized OrionConfigurationModification update(UnaryOperator<OrionDocument> update) {
        requireOpen();
        modify(Objects.requireNonNull(update, "update").apply(document));
        return this;
    }

    @Override
    public synchronized void apply() {
        requireOpen();
        try {
            revision = Objects.requireNonNull(onSave.apply(document), "saved revision");
        } finally {
            discard();
        }
    }

    @Override
    public synchronized void discard() {
        document = null;
    }

    private void requireOpen() {
        if (document == null) {
            throw new IllegalStateException("Configuration modification is already finished");
        }
    }
}
