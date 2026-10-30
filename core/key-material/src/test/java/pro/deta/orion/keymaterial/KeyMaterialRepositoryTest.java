package pro.deta.orion.keymaterial;

import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.storage.ReadOnlyBlobStorage;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KeyMaterialRepositoryTest {
    @Test
    void usesTheSourceRevisionAsTheModificationBase() throws Exception {
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             KeyMaterialCodec codec = new KeyMaterialCodec(options)) {
            byte[] bytes = codec.encode(codec.create());
            try {
                ReadOnlyBlobStorage source = new ReadOnlyBlobStorage(bytes, "test");
                String revision = source.load().revision();
                try (OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialRepository(
                        source, new KeyMaterialCodec(options), new OrionEventManager()))) {
                    assertThat(material.revision()).isEqualTo(revision);
                    KeyMaterialModification modification = material.modify(material.revision());
                    assertThat(modification.version()).isEqualTo(revision);
                    modification.apply();
                    assertThat(material.revision()).isEqualTo(revision);
                }
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    @Test
    void observesAnUpdateBetweenInitialReadAndEventSubscription() throws Exception {
        KeyMaterialDescriptor first = new KeyMaterialDescriptor("first",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor second = new KeyMaterialDescriptor("second",
                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA, 1);
        InMemoryBlobStorage writerSource = new InMemoryBlobStorage();
        byte[] initial = null;
        byte[] updated = null;
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             OrionKeyMaterial writer = OrionKeyMaterial.open(new KeyMaterialRepository(
                     writerSource, new KeyMaterialCodec(options), new OrionEventManager()))) {
            writer.withModification(writer.revision(), modification -> {
                modification.generateKey(first, 2048);
                modification.apply();
            });
            initial = writerSource.load().bytes();
            writer.withModification(writer.revision(), modification -> {
                modification.generateKey(second, 2048);
                modification.apply();
            });
            updated = writerSource.load().bytes();
            byte[] nextContent = updated;
            InMemoryBlobStorage readerSource = new InMemoryBlobStorage() {
                private boolean updatedDuringRead;

                @Override
                public synchronized byte[] read(String revision) throws IOException {
                    byte[] bytes = super.read(revision);
                    if (!updatedDuringRead) {
                        updatedDuringRead = true;
                        super.write(new BlobStorage.Modification(revision, nextContent));
                    }
                    return bytes;
                }
            };
            readerSource.write(new BlobStorage.Modification(null, initial));
            try (OrionKeyMaterial reader = OrionKeyMaterial.open(new KeyMaterialRepository(
                    readerSource, new KeyMaterialCodec(options), new OrionEventManager()))) {
                assertThat(reader.revision()).isEqualTo("2");
                assertThat(reader.contains(first)).isTrue();
                assertThat(reader.contains(second)).isTrue();
            }
        } finally {
            if (initial != null) Arrays.fill(initial, (byte) 0);
            if (updated != null) Arrays.fill(updated, (byte) 0);
        }
    }
}
