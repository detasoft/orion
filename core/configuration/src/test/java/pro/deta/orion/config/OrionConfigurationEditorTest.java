package pro.deta.orion.config;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.ConfigurationSecret;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.util.Result;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionConfigurationEditorTest {
    @Test
    void loadsAndObservesConfigurationWithoutAnAclService() {
        try (Fixture fixture = new Fixture()) {
            fixture.editor.onStart();
            assertThat(fixture.editor.current()).isSameAs(fixture.storage.snapshot);
            OrionDocumentRevision updated = snapshot("updated");
            fixture.storage.snapshot = updated;
            fixture.events.publishAndWait(new ConfigurationChanged(fixture.storage, updated));
            assertThat(fixture.editor.current()).isSameAs(updated);
            assertThat(fixture.editor.status().activeRevision()).contains("updated");
        }
    }

    @Test
    void observesAChangePublishedDuringTheInitialRead() {
        try (Fixture fixture = new Fixture()) {
            OrionDocumentRevision updated = snapshot("during-startup");
            fixture.storage.afterLoad = () -> {
                fixture.storage.snapshot = updated;
                fixture.events.publish(new ConfigurationChanged(fixture.storage, updated));
            };
            fixture.editor.onStart();
            fixture.events.publishAndWait(new OrionEvent() {});
            assertThat(fixture.editor.current()).isSameAs(updated);
        }
    }

    @Test
    void ignoresOtherStorageAndEventsAfterStopping() {
        try (Fixture fixture = new Fixture()) {
            fixture.editor.onStart();
            OrionDocumentRevision initial = fixture.editor.current();
            MemoryStorage other = new MemoryStorage(fixture.events);
            fixture.events.publishAndWait(new ConfigurationChanged(other, snapshot("other")));
            assertThat(fixture.editor.current()).isSameAs(initial);
            fixture.editor.onStop();
            fixture.events.publishAndWait(new ConfigurationChanged(fixture.storage, snapshot("after-stop")));
            assertThat(fixture.editor.current()).isSameAs(initial);
            assertThat(fixture.editor.isRunning()).isFalse();
        }
    }

    @Test
    void retainsLastValidConfigurationWhenDecodedDocumentCannotBeValidated() {
        try (Fixture fixture = new Fixture()) {
            fixture.editor.onStart();
            OrionDocumentRevision initial = fixture.editor.current();
            OrionDocument document = OrionDocument.withAccessControl(new AccessControl());
            OrionDocument.SystemConfiguration system = document.system();
            OrionDocument invalid = new OrionDocument(new OrionDocument.SystemConfiguration(
                    system.accessControl(), system.https(),
                    List.of(new ConfigurationSecret("invalid", "not-an-envelope")),
                    system.proxies(), system.connections(), system.oidcProviders(), system.invitations()),
                    document.organizations());
            fixture.storage.snapshot = new OrionDocumentRevision(Result.of(invalid), new OrionRevision.Stored("bad"));
            fixture.events.publishAndWait(new ConfigurationChanged(fixture.storage, fixture.storage.snapshot));
            assertThat(fixture.editor.current()).isSameAs(initial);
            assertThat(fixture.editor.status().storedRevision()).contains("bad");
            assertThat(fixture.editor.status().validation()).isEqualTo("invalid");
        }
    }

    @Test
    void missingDocumentKeepsItsRevisionAndBecomesCurrentOnlyAfterSaving() {
        try (Fixture fixture = new Fixture()) {
            fixture.storage.snapshot = new OrionDocumentRevision(
                    new Result.Failure<>(Result.FailureCode.NOT_FOUND), new OrionRevision.Stored("existing-branch"));
            fixture.editor.onStart();
            assertThat(fixture.editor.isRunning()).isTrue();
            assertThat(fixture.editor.isInitialized()).isFalse();
            assertThatThrownBy(fixture.editor::current).isInstanceOf(IllegalStateException.class);
            OrionDocumentRevision initial = new OrionDocumentRevision(
                    snapshot("unused").result(), fixture.editor.load().revision());
            OrionConfigurationModification edit = fixture.editor.edit(initial, ChangeMetadata.EMPTY);
            assertThat(fixture.editor.isInitialized()).isFalse();
            edit.apply();
            fixture.events.publishAndWait(new OrionEvent() {});
            assertThat(fixture.storage.expected).isEqualTo(new OrionRevision.Stored("existing-branch"));
            assertThat(fixture.editor.current().revision()).isEqualTo(edit.revision());
        }
    }

    @Test
    void failedStartupClosesSubscriptionAndCanBeRetried() {
        try (Fixture fixture = new Fixture()) {
            fixture.storage.unavailable = true;
            assertThatThrownBy(fixture.editor::onStart).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.editor.isRunning()).isFalse();
            fixture.events.publishAndWait(new ConfigurationChanged(fixture.storage, snapshot("ignored")));
            assertThat(fixture.editor.isInitialized()).isFalse();
            fixture.storage.unavailable = false;
            fixture.editor.onStart();
            assertThat(fixture.editor.current()).isSameAs(fixture.storage.snapshot);
            OrionConfigurationModification edit = fixture.editor.edit(fixture.editor.current(), ChangeMetadata.EMPTY);
            edit.update(document -> OrionDocument.withAccessControl(new AccessControl()));
            edit.discard();
            assertThat(fixture.editor.current()).isSameAs(fixture.storage.snapshot);
            assertThat(fixture.storage.expected).isNull();
        }
    }

    @Test
    void restartWithAMissingDocumentDoesNotReuseThePreviousConfiguration() {
        try (Fixture fixture = new Fixture()) {
            fixture.editor.onStart();
            fixture.editor.onStop();
            fixture.storage.snapshot = new OrionDocumentRevision(
                    new Result.Failure<>(Result.FailureCode.NOT_FOUND), new OrionRevision.Stored("without-file"));
            fixture.editor.onStart();
            assertThat(fixture.editor.isRunning()).isTrue();
            assertThat(fixture.editor.isInitialized()).isFalse();
            assertThat(fixture.editor.load().revision()).isEqualTo(new OrionRevision.Stored("without-file"));
        }
    }

    private static OrionDocumentRevision snapshot(String revision) {
        return new OrionDocumentRevision(Result.of(OrionDocument.withAccessControl(new AccessControl())),
                new OrionRevision.Stored(revision));
    }

    private static final class Fixture implements AutoCloseable {
        private final OrionEventManager events = new OrionEventManager();
        private final MemoryStorage storage = new MemoryStorage(events);
        private final OrionConfigurationEditor editor =
                new OrionConfigurationEditor(storage, OrionKeyMaterial.unavailable(), events);

        private Fixture() {
            events.onStart();
        }

        @Override
        public void close() {
            editor.onStop();
            events.onStop();
        }
    }

    private static final class MemoryStorage implements OrionVersionedConfigurationStorage {
        private final OrionEventManager events;
        private OrionDocumentRevision snapshot = snapshot("initial");
        private OrionRevision expected;
        private boolean unavailable;
        private Runnable afterLoad;

        private MemoryStorage(OrionEventManager events) {
            this.events = events;
        }

        @Override
        public OrionDocumentRevision load() {
            if (unavailable) throw new IllegalStateException("Storage is unavailable");
            OrionDocumentRevision read = snapshot;
            if (afterLoad != null) {
                Runnable action = afterLoad;
                afterLoad = null;
                action.run();
            }
            return read;
        }

        @Override
        public OrionDocumentRevision load(OrionRevision.Stored revision) {
            return snapshot;
        }

        @Override
        public OrionRevision.Stored save(OrionRevision expected, OrionDocument document, ChangeMetadata metadata) {
            assertThat(expected).isEqualTo(snapshot.revision());
            this.expected = expected;
            OrionRevision.Stored saved = new OrionRevision.Stored("saved");
            snapshot = new OrionDocumentRevision(Result.of(document), saved);
            events.publish(new ConfigurationChanged(this, snapshot));
            return saved;
        }
    }
}
