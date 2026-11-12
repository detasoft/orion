package pro.deta.orion.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.test.util.OrionTestRuntime;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BlobOrionConfigurationStorageTest {
    @ParameterizedTest
    @ValueSource(strings = {"<orion", "<orion schemaVersion=\"999\"/>", "<orion schemaVersion=\"2\"/>"})
    void reportsUnreadableDocumentsAsInvalidContent(String xml) {
        try (OrionTestRuntime runtime = new OrionTestRuntime();
             BlobOrionConfigurationStorage storage = new BlobOrionConfigurationStorage(
                     new InMemoryBlobStorage(xml.getBytes(StandardCharsets.UTF_8), true, runtime.events()),
                     runtime.events())) {
            OrionDocumentRevision snapshot = storage.load();
            assertThat(snapshot.revision()).isEqualTo(new VcRevision.Stored("1"));
            assertThat(snapshot.result()).isInstanceOfSatisfying(Result.Failure.class,
                    failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.INVALID_CONTENT));
            OrionConfigurationEditor editor =
                    new OrionConfigurationEditor(storage, OrionKeyMaterial.unavailable(), runtime.events());
            assertThat(editor.status().validation()).isEqualTo("invalid");
            assertThat(editor.status().storedRevision()).contains("1");
            assertThat(editor.status().activeRevision()).isEmpty();
        }
    }

    @Test
    void retainsLastValidDocumentAndRecoversAfterMalformedContent() throws IOException {
        try (OrionTestRuntime runtime = new OrionTestRuntime()) {
            InMemoryBlobStorage source = new InMemoryBlobStorage(validXml(), false, runtime.events());
            try (BlobOrionConfigurationStorage storage = new BlobOrionConfigurationStorage(source, runtime.events())) {
                OrionConfigurationEditor editor =
                        new OrionConfigurationEditor(storage, OrionKeyMaterial.unavailable(), runtime.events());
                runtime.own(editor::onStop);
                editor.onStart();
                OrionDocumentRevision initial = editor.current();
                VcRevision.Stored invalid = write(source, "<orion".getBytes(StandardCharsets.UTF_8));
                runtime.awaitEvents();
                assertThat(editor.current()).isSameAs(initial);
                assertThat(editor.status().storedRevision()).contains(invalid.value());
                assertThat(editor.status().activeRevision()).contains("1");
                assertThat(editor.status().validation()).isEqualTo("invalid");

                VcRevision.Stored repaired = write(source, validXml());
                runtime.awaitEvents();
                assertThat(editor.current().revision()).isEqualTo(repaired);
                assertThat(editor.status().storedRevision()).contains(repaired.value());
                assertThat(editor.status().activeRevision()).contains(repaired.value());
                assertThat(editor.status().validation()).isEqualTo("valid");
            }
        }
    }

    @Test
    void missingSourceRemainsUnavailable() {
        try (OrionTestRuntime runtime = new OrionTestRuntime();
             BlobOrionConfigurationStorage storage = new BlobOrionConfigurationStorage(
                     new InMemoryBlobStorage(runtime.events()), runtime.events())) {
            assertThat(storage.load().result()).isInstanceOfSatisfying(Result.Failure.class,
                    failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.NOT_FOUND));
            OrionConfigurationEditor editor =
                    new OrionConfigurationEditor(storage, OrionKeyMaterial.unavailable(), runtime.events());
            assertThat(editor.status().validation()).isEqualTo("unavailable");
            assertThat(editor.status().storedRevision()).isEmpty();
        }
    }

    @Test
    void sourceReadFailureRemainsUnavailable() {
        try (OrionTestRuntime runtime = new OrionTestRuntime();
             BlobOrionConfigurationStorage storage =
                     new BlobOrionConfigurationStorage(new UnreadableBlob(runtime.events()), runtime.events())) {
            assertThat(storage.load().result()).isInstanceOfSatisfying(Result.Failure.class,
                    failure -> assertThat(failure.code()).isEqualTo(Result.FailureCode.GENERAL));
            OrionConfigurationEditor editor =
                    new OrionConfigurationEditor(storage, OrionKeyMaterial.unavailable(), runtime.events());
            assertThat(editor.status().validation()).isEqualTo("unavailable");
            assertThat(editor.status().storedRevision()).contains("unreadable");
        }
    }

    private static byte[] validXml() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new OrionDocumentCodec().write(OrionDocument.withAccessControl(new AccessControl()), output);
        return output.toByteArray();
    }

    private static VcRevision.Stored write(InMemoryBlobStorage source, byte[] content) {
        try (BlobStorage.BlobStorageOperations operation = source.open(new VcRevision.Latest())) {
            operation.write(content);
            return operation.commit(VcChangeDescription.EMPTY);
        }
    }

    private static final class UnreadableBlob extends BlobStorage {
        private UnreadableBlob(OrionEventManager events) {
            super(events);
        }

        @Override
        protected BlobStorageOperations createOperation(VcRevision.ReadTarget target) {
            return new BlobStorageOperations(new VcRevision.Stored("unreadable")) {
                @Override
                public byte[] readContent() {
                    throw new UncheckedIOException(new IOException("Source read failed"));
                }

                @Override
                public void write(byte[] bytes) {
                    throw new UnsupportedOperationException();
                }

                @Override
                protected VcRevision.Stored commitChanges(VcChangeDescription description) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {}
            };
        }
    }
}
