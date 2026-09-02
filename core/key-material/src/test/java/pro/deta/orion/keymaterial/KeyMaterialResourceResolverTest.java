package pro.deta.orion.keymaterial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        KeyMaterialContentStore fileStore =
                resolver.resolveStore(KeyMaterialTestConstants.fileReference(filePath));
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
        String reference = KeyMaterialTestConstants.contentBase64Reference(bytes("inline"));

        KeyMaterialContentStore store = resolver.resolveStore(reference);

        assertThat(store.read().orElseThrow().bytes()).isEqualTo(bytes("inline"));
        assertThatThrownBy(() -> store.write(bytes("update"), store.read().orElseThrow().version()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("read-only")
                .hasMessageNotContaining(reference);
    }

    @Test
    void resolvesEnvironmentAndFilePasswords() throws Exception {
        Path passwordFile = tempDir.resolve(KeyMaterialTestConstants.PASSWORD_FILE_NAME);
        Files.writeString(passwordFile, KeyMaterialTestConstants.PASSWORD_WITH_LINE_BREAK);
        setOwnerOnly(passwordFile);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(
                Map.of(
                        KeyMaterialTestConstants.ORION_KEYSTORE_PASSWORD_ENV,
                        KeyMaterialTestConstants.PASSWORD_VALUE));

        assertThat(resolver.resolvePassword(KeyMaterialTestConstants.envReference(
                KeyMaterialTestConstants.ORION_KEYSTORE_PASSWORD_ENV)))
                .isEqualTo(KeyMaterialTestConstants.password());
        assertThat(resolver.resolvePassword(KeyMaterialTestConstants.fileReference(passwordFile)))
                .isEqualTo(KeyMaterialTestConstants.password());
        try (KeyMaterialOptions options = resolver.pkcs12Options(
                KeyMaterialTestConstants.envReference(KeyMaterialTestConstants.ORION_KEYSTORE_PASSWORD_ENV),
                true)) {
            assertThat(options.password()).isEqualTo(KeyMaterialTestConstants.password());
        }
    }

    @Test
    void safeResolverRejectsPlaintextAndInlinePasswordsWithoutExposingThem() {
        String plaintext = "diagnostic-secret";
        String inline = "content:" + plaintext;
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolvePassword(plaintext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled")
                .hasMessageNotContaining(plaintext);
        assertThatThrownBy(() -> resolver.resolvePassword(inline))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled")
                .hasMessageNotContaining(plaintext);
    }

    @Test
    void explicitUnsafeResolverAllowsPlaintextAndInlinePasswords() throws Exception {
        KeyMaterialResourceResolver resolver =
                KeyMaterialResourceResolver.unsafe(Map.of());

        assertThat(resolver.resolvePassword(KeyMaterialTestConstants.PASSWORD_VALUE))
                .isEqualTo(KeyMaterialTestConstants.password());
        assertThat(resolver.resolvePassword(KeyMaterialTestConstants.contentBase64Reference(
                bytes(KeyMaterialTestConstants.PASSWORD_VALUE))))
                .isEqualTo(KeyMaterialTestConstants.password());
    }

    @Test
    void safeResolverRejectsUnprotectedPasswordFile() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "POSIX permissions are not available");
        Path passwordFile = tempDir.resolve(KeyMaterialTestConstants.PASSWORD_FILE_NAME);
        Files.writeString(passwordFile, KeyMaterialTestConstants.PASSWORD_VALUE);
        Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-r--r--"));
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolvePassword(
                KeyMaterialTestConstants.fileReference(passwordFile)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readable only by its owner")
                .hasMessageNotContaining(KeyMaterialTestConstants.PASSWORD_VALUE);
    }

    @Test
    void safeResolverRejectsPasswordFileInWritableDirectory() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "POSIX permissions are not available");
        Path exposedDirectory = tempDir.resolve("exposed");
        Files.createDirectory(exposedDirectory);
        Files.setPosixFilePermissions(exposedDirectory, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path passwordFile = exposedDirectory.resolve(KeyMaterialTestConstants.PASSWORD_FILE_NAME);
        Files.writeString(passwordFile, KeyMaterialTestConstants.PASSWORD_VALUE);
        setOwnerOnly(passwordFile);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolvePassword(
                KeyMaterialTestConstants.fileReference(passwordFile)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("writable by another user")
                .hasMessageNotContaining(KeyMaterialTestConstants.PASSWORD_VALUE);
    }

    @Test
    void safeResolverRejectsPasswordFileSymbolicLinks() throws Exception {
        assumeTrue(KeyMaterialFileSecurity.supportsPosix(tempDir), "Symbolic links are not available");
        Path passwordFile = tempDir.resolve(KeyMaterialTestConstants.PASSWORD_FILE_NAME);
        Files.writeString(passwordFile, KeyMaterialTestConstants.PASSWORD_VALUE);
        setOwnerOnly(passwordFile);
        Path link = tempDir.resolve("password-link.txt");
        Files.createSymbolicLink(link, passwordFile.getFileName());
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolvePassword(KeyMaterialTestConstants.fileReference(link)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("must not contain symbolic links")
                .hasMessageNotContaining(KeyMaterialTestConstants.PASSWORD_VALUE);
    }

    private static void setOwnerOnly(Path path) throws Exception {
        if (KeyMaterialFileSecurity.supportsPosix(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
