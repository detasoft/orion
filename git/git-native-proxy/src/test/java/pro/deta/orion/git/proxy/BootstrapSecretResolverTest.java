package pro.deta.orion.git.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapSecretResolverTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesEnvironmentAndClearsOwnedCharacters() throws Exception {
        BootstrapSecretResolver resolver = new BootstrapSecretResolver(
                Map.of("TOKEN", "sensitive-token"));

        BootstrapSecret secret = resolver.resolve("Remote Git credential", "env:TOKEN");
        char[] exposed = secret.copy();
        char[] owned = ownedValue(secret);
        assertThat(exposed).containsExactly("sensitive-token".toCharArray());

        secret.close();

        assertThat(owned).containsOnly('\0');
        java.util.Arrays.fill(exposed, '\0');
    }

    @Test
    void resolvesOwnerOnlyFileAndTrimsOneLineEnding() throws Exception {
        Path file = tempDir.toRealPath().resolve("credential");
        Files.writeString(file, "file-secret\r\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

        try (BootstrapSecret secret = new BootstrapSecretResolver(Map.of())
                .resolve("Remote Git credential", file.toUri().toString())) {
            assertThat(secret.copy()).containsExactly("file-secret".toCharArray());
        }
    }

    @Test
    void rejectsMissingOrPlaintextReferencesWithoutLeakingValues() {
        BootstrapSecretResolver resolver = new BootstrapSecretResolver(Map.of());

        assertThatThrownBy(() -> resolver.resolve("Remote Git credential", "plain-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git credential must use env: or file:")
                .hasMessageNotContaining("plain-secret");
        assertThatThrownBy(() -> resolver.resolve("Remote Git credential", "env:MISSING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git credential environment variable is not set")
                .hasMessageNotContaining("MISSING");
    }

    @Test
    void rejectsSecretFileReadableByOtherUsersWithoutLeakingContent() throws Exception {
        Path file = tempDir.toRealPath().resolve("credential");
        Files.writeString(file, "exposed-secret");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertThatThrownBy(() -> new BootstrapSecretResolver(Map.of())
                .resolve("Remote Git credential", file.toUri().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git credential file must be owner-only")
                .hasMessageNotContaining("exposed-secret");
    }

    @Test
    void rejectsSymbolicLinksInSecretPath() throws Exception {
        Path root = tempDir.toRealPath();
        Path target = root.resolve("credential-target");
        Files.writeString(target, "protected-secret");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        Path directLink = root.resolve("credential-link");
        Files.createSymbolicLink(directLink, target.getFileName());
        Path linkedDirectory = root.resolve("linked-directory");
        Files.createSymbolicLink(linkedDirectory, root);

        BootstrapSecretResolver resolver = new BootstrapSecretResolver(Map.of());
        assertThatThrownBy(() -> resolver.resolve(
                "Remote Git credential",
                directLink.toUri().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("protected-secret");
        assertThatThrownBy(() -> resolver.resolve(
                "Remote Git credential",
                linkedDirectory.resolve(target.getFileName()).toUri().toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("protected-secret");
    }

    @Test
    void rejectsGrowthWhileReadingAndClearsTheSizedBuffer() {
        byte[] sized = new byte[2];

        assertThatThrownBy(() -> BootstrapSecretResolver.readSized(
                Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2, 3})),
                sized,
                "Remote Git credential"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Remote Git credential file changed while it was read");
        assertThat(sized).containsOnly((byte) 0);
    }

    @Test
    void clearsOriginalSizedBufferAfterEarlyEof() throws Exception {
        byte[] sized = new byte[4];

        byte[] read = BootstrapSecretResolver.readSized(
                Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2})),
                sized,
                "Remote Git credential");

        assertThat(read).containsExactly((byte) 1, (byte) 2);
        assertThat(sized).containsOnly((byte) 0);
        Arrays.fill(read, (byte) 0);
    }

    private static char[] ownedValue(BootstrapSecret secret) throws Exception {
        Field field = BootstrapSecret.class.getDeclaredField("value");
        field.setAccessible(true);
        return (char[]) field.get(secret);
    }
}
