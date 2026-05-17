package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialContentStoreTest {
    @TempDir
    private Path tempDir;

    @Test
    void inMemoryStoreVersionsIncrementAndRejectStaleWrites() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        String firstVersion = store.write(bytes("first"), null);
        KeyMaterialSnapshot firstSnapshot = store.read().orElseThrow();

        assertThat(firstSnapshot.version()).isEqualTo(firstVersion);
        assertThat(firstSnapshot.bytes()).isEqualTo(bytes("first"));
        assertThatThrownBy(() -> store.write(bytes("stale"), "missing"))
                .isInstanceOf(KeyMaterialStoreConflictException.class);

        String secondVersion = store.write(bytes("second"), firstVersion);

        assertThat(secondVersion).isNotEqualTo(firstVersion);
        assertThat(store.read().orElseThrow().bytes()).isEqualTo(bytes("second"));
    }

    @Test
    void localStoreVersionsTrackContentAndRejectSameSizeStaleWrites() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore store = new LocalKeyMaterialContentStore(path);

        String firstVersion = store.write(bytes("aaaa"), null);
        KeyMaterialSnapshot firstSnapshot = store.read().orElseThrow();

        Files.writeString(path, "bbbb", StandardCharsets.UTF_8);
        KeyMaterialSnapshot changedSnapshot = store.read().orElseThrow();

        assertThat(firstSnapshot.version()).isEqualTo(firstVersion);
        assertThat(changedSnapshot.version()).isNotEqualTo(firstSnapshot.version());
        assertThat(changedSnapshot.bytes()).isEqualTo(bytes("bbbb"));
        assertThatThrownBy(() -> store.write(bytes("cccc"), firstSnapshot.version()))
                .isInstanceOf(KeyMaterialStoreConflictException.class)
                .hasMessageContaining("changed before save");
    }

    @Test
    void localStoreRejectsStaleWritesAfterAnotherWriterSaves() throws Exception {
        Path path = tempDir.resolve(KeyMaterialTestConstants.KEY_STORE_FILE_NAME);
        LocalKeyMaterialContentStore first = new LocalKeyMaterialContentStore(path);
        LocalKeyMaterialContentStore second = new LocalKeyMaterialContentStore(path);

        String initialVersion = first.write(bytes("first"), null);
        String secondVersion = second.write(bytes("second"), initialVersion);

        assertThatThrownBy(() -> first.write(bytes("stale"), initialVersion))
                .isInstanceOf(KeyMaterialStoreConflictException.class);
        assertThat(second.read().orElseThrow().version()).isEqualTo(secondVersion);
        assertThat(second.read().orElseThrow().bytes()).isEqualTo(bytes("second"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
