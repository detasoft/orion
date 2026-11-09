package pro.deta.orion.keymaterial;

import pro.deta.orion.keymaterial.KeyMaterialTestRuntime.TestBlobStorage;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcRevision;

import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.read;
import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.write;
import static pro.deta.orion.keymaterial.KeyMaterialTestRuntime.revision;

import pro.deta.orion.storage.InMemoryBlobStorage;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class KeyMaterialRepositoryTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final KeyMaterialTestRuntime runtime = new KeyMaterialTestRuntime();

    @Test
    void firstCommitPersistsMaterialAndRejectsAnotherCreator() throws Exception {
        InMemoryBlobStorage source = new InMemoryBlobStorage(runtime.events);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
                KeyMaterialRepository repository = new KeyMaterialRepository(
                        source, new KeyMaterialCodec(options), runtime.events);
                KeyMaterialOperation first = repository.open(new VcRevision.Latest());
                KeyMaterialOperation stale = repository.open(new VcRevision.Absent())) {
            assertThat(first.revision()).isEqualTo(new VcRevision.Absent());
            assertThat(repository.hasDurableSnapshot()).isFalse();
            first.generateKey("first", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256);
            stale.generateKey("stale", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER), KeyMaterialAlgorithm.AES, 256);
            VcRevision.Stored saved = first.commit(VcChangeDescription.EMPTY);
            assertThat(repository.revision()).isEqualTo(saved.value());
            assertThat(repository.hasDurableSnapshot()).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> stale.commit(VcChangeDescription.EMPTY))
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(pro.deta.orion.storage.BlobStorageConflictException.class);
            try (KeyMaterialRepository reopened = new KeyMaterialRepository(
                    source, new KeyMaterialCodec(options), runtime.events)) {
                assertThat(reopened.find("first")).isPresent();
                assertThat(reopened.find("stale")).isEmpty();
            }
        }
    }

    @Test
    void usesTheSourceRevisionAsTheModificationBase() throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             KeyMaterialCodec codec = new KeyMaterialCodec(options)) {
            byte[] bytes = codec.encode(codec.create());
            try {
                InMemoryBlobStorage source = new InMemoryBlobStorage(bytes, true, runtime.events);
                String revision = revision(source);
                try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                        source, new KeyMaterialCodec(options), runtime.events))) {
                    assertThat(material.revision()).isEqualTo(revision);
                    KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()));
                    assertThat(modification.revision()).isEqualTo(new VcRevision.Stored(revision));
                    modification.commit(VcChangeDescription.EMPTY);
                    assertThat(material.revision()).isEqualTo(revision);
                }
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    @Test
    void observesAnUpdateBetweenInitialReadAndEventSubscription() throws Exception {
        KeyMaterialId first = new KeyMaterialId("first", 1);
        KeyMaterialId second = new KeyMaterialId("second", 1);
        InMemoryBlobStorage writerSource = new TestBlobStorage(runtime.events);
        byte[] initial = null;
        byte[] updated = null;
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             OrionKeyMaterial writer = OrionKeyMaterial.open(new KeyMaterialRepository(
                     writerSource, new KeyMaterialCodec(options), runtime.events))) {
            try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                modification.generateKey("first", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            initial = read(writerSource);
            try (KeyMaterialOperation modification = writer.open(new VcRevision.Stored(writer.revision()))) {
                modification.generateKey("second", Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 2048);
                modification.commit(VcChangeDescription.EMPTY);
            }
            updated = read(writerSource);
            byte[] nextContent = updated;
            InMemoryBlobStorage readerSource = new TestBlobStorage(runtime.events) {
                private boolean updatedDuringRead;

                @Override
                protected byte[] readSnapshot(BlobStorageOperations operation) {
                    byte[] bytes = operation.readContent();
                    if (!updatedDuringRead) {
                        updatedDuringRead = true;
                        try (BlobStorageOperations update = super.createOperation(
                                assertInstanceOf(VcRevision.Stored.class, operation.revision()))) {
                            update.write(nextContent);
                            update.commit(VcChangeDescription.EMPTY);
                        }
                    }
                    return bytes;
                }
            };
            write(readerSource, null, initial);
            try (OrionKeyMaterial reader = OrionKeyMaterial.open(new KeyMaterialRepository(
                    readerSource, new KeyMaterialCodec(options), runtime.events))) {
                assertThat(reader.revision()).isEqualTo("3");
                assertThat(reader.contains(first)).isTrue();
                assertThat(reader.contains(second)).isTrue();
            }
        } finally {
            if (initial != null) Arrays.fill(initial, (byte) 0);
            if (updated != null) Arrays.fill(updated, (byte) 0);
        }
    }
    @Test
    void openedOperationKeepsItsSnapshotAndRejectsAStaleCommit() throws Exception {
        TestBlobStorage source = new TestBlobStorage(runtime.events);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
                KeyMaterialRepository repository = new KeyMaterialRepository(
                        source, new KeyMaterialCodec(options), runtime.events);
                KeyMaterialOperation reader = repository.open(new VcRevision.Latest());
                KeyMaterialOperation writer = repository.open(new VcRevision.Latest())) {
            writer.generateKey("saved", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER),
                    KeyMaterialAlgorithm.AES, 256);
            VcRevision.Stored saved = writer.commit(VcChangeDescription.EMPTY);
            assertThat(reader.contains("saved")).isFalse();
            assertThat(repository.find("saved")).isPresent();
            reader.generateKey("stale", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER),
                    KeyMaterialAlgorithm.AES, 256);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> reader.commit(VcChangeDescription.EMPTY))
                    .isInstanceOf(java.io.UncheckedIOException.class)
                    .hasCauseInstanceOf(pro.deta.orion.storage.BlobStorageConflictException.class);
            assertThat(repository.revision()).isEqualTo(saved.value());
            assertThat(repository.find("stale")).isEmpty();
        }
    }

    @Test
    void changesAfterCommitStayPrivateUntilTheOperationIsClosed() throws Exception {
        TestBlobStorage source = new TestBlobStorage(runtime.events);
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
                KeyMaterialRepository repository = new KeyMaterialRepository(
                        source, new KeyMaterialCodec(options), runtime.events)) {
            byte[] saved;
            try (KeyMaterialOperation operation = repository.open(new VcRevision.Latest())) {
                operation.generateKey("saved", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER),
                        KeyMaterialAlgorithm.AES, 256);
                operation.commit(VcChangeDescription.EMPTY);
                saved = read(source);
                operation.generateKey("pending", Set.of(KeyMaterialPurpose.CONFIGURATION_CIPHER),
                        KeyMaterialAlgorithm.AES, 256);
                assertThat(operation.contains("pending")).isTrue();
                assertThat(repository.find("pending")).isEmpty();
                assertThat(read(source)).isEqualTo(saved);
            }
            assertThat(repository.find("pending")).isEmpty();
            assertThat(read(source)).isEqualTo(saved);
        }
    }

}
