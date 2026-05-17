package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeyMaterialResourceResolverTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesPlainFileAndEnvironmentLocationsToLocalStore() {
        Path plainPath = tempDir.resolve(KeyMaterialTestConstants.PLAIN_KEY_STORE_FILE_NAME);
        Path filePath = tempDir.resolve(KeyMaterialTestConstants.FILE_KEY_STORE_FILE_NAME);
        Path envPath = tempDir.resolve(KeyMaterialTestConstants.ENV_KEY_STORE_FILE_NAME);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(
                Map.of(KeyMaterialTestConstants.ORION_KEYSTORE_LOCATION_ENV, envPath.toString()));

        LocalKeyMaterialContentStore plainStore = (LocalKeyMaterialContentStore) resolver.resolveStore(plainPath.toString());
        LocalKeyMaterialContentStore fileStore =
                (LocalKeyMaterialContentStore) resolver.resolveStore(KeyMaterialTestConstants.fileReference(filePath));
        LocalKeyMaterialContentStore envStore = (LocalKeyMaterialContentStore) resolver.resolveStore(
                KeyMaterialTestConstants.envReference(KeyMaterialTestConstants.ORION_KEYSTORE_LOCATION_ENV));

        assertThat(plainStore.path()).isEqualTo(plainPath.toAbsolutePath().normalize());
        assertThat(fileStore.path()).isEqualTo(filePath.toAbsolutePath().normalize());
        assertThat(envStore.path()).isEqualTo(envPath.toAbsolutePath().normalize());
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
        assertThat(resolver.pkcs12Options(
                KeyMaterialTestConstants.envReference(KeyMaterialTestConstants.ORION_KEYSTORE_PASSWORD_ENV),
                true).password()).isEqualTo(KeyMaterialTestConstants.password());
    }
}
