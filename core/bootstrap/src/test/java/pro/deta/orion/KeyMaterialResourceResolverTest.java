package pro.deta.orion;

import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.keymaterial.KeyMaterialOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KeyMaterialResourceResolverTest {
    @TempDir
    private Path tempDir;

    @Test
    void decodesUnicodePasswordAndTrimsOneLineEnding() throws Exception {
        Path file = tempDir.toRealPath().resolve("password");
        Files.write(file, bytes("sëcret🔑\r\n"));
        setOwnerOnly(file);

        assertThat(KeyMaterialResourceResolver.standard().resolvePassword(file.toUri().toString()))
                .isEqualTo("sëcret🔑".toCharArray());
    }

    @Test
    void rejectsPasswordFilesWithInvalidOrTruncatedUtf8() throws Exception {
        Path file = tempDir.toRealPath().resolve("password");
        for (byte[] encoded : List.of(new byte[]{'s', (byte) 0xc3, 0x28},
                new byte[]{'s', (byte) 0xe2, (byte) 0x82})) {
            Files.write(file, encoded);
            setOwnerOnly(file);

            assertThatThrownBy(() -> KeyMaterialResourceResolver.standard()
                    .resolvePassword(file.toUri().toString()))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("Key material password is not valid UTF-8");
        }
    }

    @Test
    void resolvesPlainFileAndEnvironmentLocationsToWritableStores() throws Exception {
        Path plainPath = tempDir.resolve("plain.p12");
        Path filePath = tempDir.resolve("file.p12");
        Path envPath = tempDir.resolve("env.p12");
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(
                Map.of("ORION_KEYSTORE_LOCATION", envPath.toString()));

        BlobStorage plainStore = resolver.resolveSource(plainPath.toString());
        BlobStorage fileStore =
                resolver.resolveSource("file:" + filePath);
        BlobStorage envStore = resolver.resolveSource(
                "env:ORION_KEYSTORE_LOCATION");

        String plainVersion = plainStore.write(new BlobStorage.Modification(null, bytes("plain")));
        String fileVersion = fileStore.write(new BlobStorage.Modification(null, bytes("file")));
        String envVersion = envStore.write(new BlobStorage.Modification(null, bytes("env")));

        assertThat(Files.readAllBytes(plainPath)).isEqualTo(bytes("plain"));
        assertThat(Files.readAllBytes(filePath)).isEqualTo(bytes("file"));
        assertThat(Files.readAllBytes(envPath)).isEqualTo(bytes("env"));
        assertThat(plainStore.load().revision()).isEqualTo(plainVersion);
        assertThat(fileStore.load().revision()).isEqualTo(fileVersion);
        assertThat(envStore.load().revision()).isEqualTo(envVersion);
    }

    @Test
    void resolvesInlineLocationToReadOnlyStore() throws Exception {
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();
        String reference = contentReference(bytes("inline"));

        BlobStorage store = resolver.resolveSource(reference);

        assertThat(store.load().bytes()).isEqualTo(bytes("inline"));
        assertThatThrownBy(() -> store.write(new BlobStorage.Modification(store.load().revision(),
                bytes("update"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("read-only")
                .hasMessageNotContaining(reference);
    }

    @Test
    void resolvesEnvironmentAndFilePasswords() throws Exception {
        Path passwordFile = tempDir.resolve("password.txt");
        Files.writeString(passwordFile, "changeit\n");
        setOwnerOnly(passwordFile);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(
                Map.of(
                        "ORION_KEYSTORE_PASSWORD",
                        "changeit"));

        assertThat(resolver.resolvePassword("env:ORION_KEYSTORE_PASSWORD"))
                .isEqualTo("changeit".toCharArray());
        assertThat(resolver.resolvePassword("file:" + passwordFile))
                .isEqualTo("changeit".toCharArray());
        try (KeyMaterialOptions options = resolver.pkcs12Options(
                "env:ORION_KEYSTORE_PASSWORD")) {
            assertThat(options.password()).isEqualTo("changeit".toCharArray());
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

        assertThat(resolver.resolvePassword("changeit"))
                .isEqualTo("changeit".toCharArray());
        assertThat(resolver.resolvePassword(contentReference(
                bytes("changeit"))))
                .isEqualTo("changeit".toCharArray());
    }

    @Test
    void safeResolverRejectsUnprotectedPasswordFile() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"), "POSIX permissions are not available");
        Path passwordFile = tempDir.resolve("password.txt");
        Files.writeString(passwordFile, "changeit");
        Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-r--r--"));
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolvePassword(
                "file:" + passwordFile))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("readable only by its owner")
                .hasMessageNotContaining("changeit");
    }

    @Test
    void safeResolverRejectsPasswordFileInWritableDirectory() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"), "POSIX permissions are not available");
        Path exposedDirectory = tempDir.resolve("exposed");
        Files.createDirectory(exposedDirectory);
        Files.setPosixFilePermissions(exposedDirectory, PosixFilePermissions.fromString("rwxrwxrwx"));
        Path passwordFile = exposedDirectory.resolve("password.txt");
        Files.writeString(passwordFile, "changeit");
        setOwnerOnly(passwordFile);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolvePassword(
                "file:" + passwordFile))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("writable by another user")
                .hasMessageNotContaining("changeit");
    }

    @Test
    void safeResolverRejectsPasswordFileSymbolicLinks() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"), "Symbolic links are not available");
        Path passwordFile = tempDir.resolve("password.txt");
        Files.writeString(passwordFile, "changeit");
        setOwnerOnly(passwordFile);
        Path link = tempDir.resolve("password-link.txt");
        Files.createSymbolicLink(link, passwordFile.getFileName());
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard();

        assertThatThrownBy(() -> resolver.resolvePassword("file:" + link))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("must not contain symbolic links")
                .hasMessageNotContaining("changeit");
    }

    private static void setOwnerOnly(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static String contentReference(byte[] bytes) {
        return "content:base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
