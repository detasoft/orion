package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KeyMaterialContentStoreTest {
    @Test
    void observesAnUpdateBetweenInitialReadAndEventSubscription() throws Exception {
        KeyMaterialDescriptor first = new KeyMaterialDescriptor("first", Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor second = new KeyMaterialDescriptor("second", Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                KeyMaterialAlgorithm.RSA, 1);
        InMemoryKeyMaterialContentSource writerSource = new InMemoryKeyMaterialContentSource();
        byte[] initial = null;
        byte[] updated = null;
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(KeyMaterialTestConstants.password());
             OrionKeyMaterial writer = OrionKeyMaterial.open(new KeyMaterialContentStore(
                     writerSource, new KeyMaterialCodec(options), new OrionEventManager()))) {
            writer.withModification(0, modification -> {
                modification.generateKey(first, 2048);
                modification.apply();
            });
            initial = writerSource.read(writerSource.revision().orElseThrow());
            writer.withModification(1, modification -> {
                modification.generateKey(second, 2048);
                modification.apply();
            });
            updated = writerSource.read(writerSource.revision().orElseThrow());
            byte[] nextContent = updated;
            InMemoryKeyMaterialContentSource readerSource = new InMemoryKeyMaterialContentSource() {
                private boolean updatedDuringRead;

                @Override
                public synchronized byte[] read(String revision) throws IOException {
                    byte[] bytes = super.read(revision);
                    if (!updatedDuringRead) {
                        updatedDuringRead = true;
                        super.write(nextContent, revision);
                    }
                    return bytes;
                }
            };
            readerSource.write(initial, null);
            try (OrionKeyMaterial reader = OrionKeyMaterial.open(new KeyMaterialContentStore(
                    readerSource, new KeyMaterialCodec(options), new OrionEventManager()))) {
                assertThat(reader.revision()).isEqualTo(2);
                assertThat(reader.contains(first)).isTrue();
                assertThat(reader.contains(second)).isTrue();
            }
        } finally {
            if (initial != null) Arrays.fill(initial, (byte) 0);
            if (updated != null) Arrays.fill(updated, (byte) 0);
        }
    }
}
