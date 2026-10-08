package pro.deta.orion.config;

import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.Modification;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Objects;

/** Stores configuration documents together with their repository revision. */
public interface OrionVersionedConfigurationStorage {
    Result<OrionDocumentRevision> load();

    Access access(OrionRevision expectedRevision, ChangeMetadata metadata);

    /** Prepares a document for one publication; discard after completion is harmless. */
    final class Access implements Modification {
        private final Function<OrionDocument, OrionRevision> onSave;
        private OrionRevision revision;
        private OrionDocument document;
        private boolean finished;

        public Access(OrionRevision revision, Function<OrionDocument, OrionRevision> onSave) {
            this.revision = Objects.requireNonNull(revision, "revision");
            this.onSave = Objects.requireNonNull(onSave, "onSave");
        }

        public synchronized OrionRevision revision() {
            return revision;
        }

        public synchronized OrionDocument modify(OrionDocument document) {
            requireOpen();
            this.document = Objects.requireNonNull(document, "document");
            return document;
        }

        @Override
        public synchronized void apply() {
            requireOpen();
            try {
                if (document == null) {
                    throw new IllegalStateException("No configuration document prepared");
                }
                revision = Objects.requireNonNull(onSave.apply(document), "saved revision");
            } finally {
                discard();
            }
        }

        @Override
        public synchronized void discard() {
            document = null;
            finished = true;
        }

        private void requireOpen() {
            if (finished) {
                throw new IllegalStateException("Configuration access is already finished");
            }
        }
    }

    ChangeSubscription onChange(Consumer<String> listener);

    @FunctionalInterface
    interface ChangeSubscription extends AutoCloseable {
        @Override
        void close();
    }
}
