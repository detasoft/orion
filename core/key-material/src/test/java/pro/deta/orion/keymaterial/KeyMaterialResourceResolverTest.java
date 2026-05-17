package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialResourceResolverTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesPlainFileAndEnvironmentLocationsToWritableStores() throws Exception {
        Path plainPath = tempDir.resolve(KeyMaterialTestConstants.PLAIN_KEY_STORE_FILE_NAME);
        Path filePath = tempDir.resolve(KeyMaterialTestConstants.FILE_KEY_STORE_FILE_NAME);
        Path envPath = tempDir.resolve(KeyMaterialTestConstants.ENV_KEY_STORE_FILE_NAME);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(
                Map.of(KeyMaterialTestConstants.ORION_KEYSTORE_LOCATION_ENV, envPath.toString()));

        KeyMaterialContentStore plainStore = resolver.resolveStore(plainPath.toString());
        KeyMaterialContentStore fileStore = resolver.resolveStore(KeyMaterialTestConstants.fileReference(filePath));
        KeyMaterialContentStore envStore = resolver.resolveStore(
                KeyMaterialTestConstants.envReference(KeyMaterialTestConstants.ORION_KEYSTORE_LOCATION_ENV));

        String plainVersion = plainStore.write(bytes("plain"), null);
        String fileVersion = fileStore.write(bytes("file"), null);
        String envVersion = envStore.write(bytes("env"), null);

        assertThat(Files.readAllBytes(plainPath)).isEqualTo(bytes("plain"));
        assertThat(Files.readAllBytes(filePath)).isEqualTo(bytes("file"));
        assertThat(Files.readAllBytes(envPath)).isEqualTo(bytes("env"));
        assertThat(plainStore.read().orElseThrow().version()).isEqualTo(plainVersion);
        assertThat(fileStore.read().orElseThrow().version()).isEqualTo(fileVersion);
        assertThat(envStore.read().orElseThrow().version()).isEqualTo(envVersion);
    }

    @Test
    void resolvesInlineLocationToReadOnlyStore() throws Exception {
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        KeyMaterialContentStore store =
                resolver.resolveStore(KeyMaterialTestConstants.contentBase64Reference(bytes("inline")));

        assertThat(store.read().orElseThrow().bytes()).isEqualTo(bytes("inline"));
        assertThatThrownBy(() -> store.write(bytes("update"), store.read().orElseThrow().version()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void resolvesEnvironmentAndFilePasswords() throws Exception {
        Path passwordFile = tempDir.resolve(KeyMaterialTestConstants.PASSWORD_FILE_NAME);
        Files.writeString(passwordFile, KeyMaterialTestConstants.PASSWORD_WITH_LINE_BREAK);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(
                Map.of(
                        KeyMaterialTestConstants.ORION_KEYSTORE_PASSWORD_ENV,
                        KeyMaterialTestConstants.PASSWORD_VALUE));

        assertThat(resolver.resolvePassword(KeyMaterialTestConstants.envReference(
                KeyMaterialTestConstants.ORION_KEYSTORE_PASSWORD_ENV))).isEqualTo(KeyMaterialTestConstants.password());
        assertThat(resolver.resolvePassword(KeyMaterialTestConstants.fileReference(passwordFile)))
                .isEqualTo(KeyMaterialTestConstants.password());
        assertThat(resolver.resolvePassword(KeyMaterialTestConstants.contentBase64Reference(
                bytes(KeyMaterialTestConstants.PASSWORD_VALUE)))).isEqualTo(KeyMaterialTestConstants.password());
        assertThat(resolver.pkcs12Options(
                KeyMaterialTestConstants.envReference(KeyMaterialTestConstants.ORION_KEYSTORE_PASSWORD_ENV),
                true).password()).isEqualTo(KeyMaterialTestConstants.password());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
