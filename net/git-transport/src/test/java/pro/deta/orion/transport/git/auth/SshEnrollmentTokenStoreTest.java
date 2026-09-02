package pro.deta.orion.transport.git.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshEnrollmentTokenStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesPrintsAndConsumesTheFirstTokenOnlyOnce() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SshEnrollmentTokenStore store = store(false, output);

        store.start();

        String token = printedToken(output);
        assertThat(Files.readString(tempDir.resolve("ssh-enrollment-token.properties"))).doesNotContain(token);
        AtomicBoolean enrolled = new AtomicBoolean();
        assertThat(store.consumeIfValid(token, () -> enrolled.set(true))).isTrue();
        assertThat(enrolled).isTrue();
        assertThat(store.consumeIfValid(token, () -> { })).isFalse();

        ByteArrayOutputStream restartedOutput = new ByteArrayOutputStream();
        SshEnrollmentTokenStore restarted = store(false, restartedOutput);
        restarted.start();
        assertThat(restartedOutput.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(restarted.consumeIfValid(token, () -> { })).isFalse();
    }

    @Test
    void retainsAnActiveTokenWithoutPrintingItOnOrdinaryRestart() {
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        SshEnrollmentTokenStore first = store(false, firstOutput);
        first.start();
        String token = printedToken(firstOutput);

        ByteArrayOutputStream restartedOutput = new ByteArrayOutputStream();
        SshEnrollmentTokenStore restarted = store(false, restartedOutput);
        restarted.start();

        assertThat(restartedOutput.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(restarted.consumeIfValid(token, () -> { })).isTrue();
    }

    @Test
    void regenerationInvalidatesThePreviousToken() {
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        SshEnrollmentTokenStore first = store(false, firstOutput);
        first.start();
        String previousToken = printedToken(firstOutput);

        ByteArrayOutputStream regeneratedOutput = new ByteArrayOutputStream();
        SshEnrollmentTokenStore regenerated = store(true, regeneratedOutput);
        regenerated.start();
        String regeneratedToken = printedToken(regeneratedOutput);

        assertThat(regeneratedToken).isNotEqualTo(previousToken);
        assertThat(regenerated.consumeIfValid(previousToken, () -> { })).isFalse();
        assertThat(regenerated.consumeIfValid(regeneratedToken, () -> { })).isTrue();
    }

    @Test
    void failedEnrollmentDoesNotConsumeTheToken() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SshEnrollmentTokenStore store = store(false, output);
        store.start();
        String token = printedToken(output);

        assertThatThrownBy(() -> store.consumeIfValid(token, () -> {
            throw new IllegalStateException("enrollment failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(store.consumeIfValid(token, () -> { })).isTrue();
    }

    @Test
    void rejectsCorruptPersistedState() throws Exception {
        Path stateFile = tempDir.resolve("ssh-enrollment-token.properties");
        Files.writeString(stateFile, "version=1\nstatus=active\n");
        if (Files.getFileStore(stateFile).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-------"));
        }

        assertThatThrownBy(() -> store(false, new ByteArrayOutputStream()).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSH enrollment token state");
    }

    @Test
    void persistsTheTokenStateWithOwnerOnlyPermissionsWhenPosixIsAvailable() throws Exception {
        SshEnrollmentTokenStore store = store(false, new ByteArrayOutputStream());

        store.start();

        Path stateFile = tempDir.resolve("ssh-enrollment-token.properties");
        if (Files.getFileStore(stateFile).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(stateFile))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    private SshEnrollmentTokenStore store(boolean regenerate, ByteArrayOutputStream output) {
        return SshEnrollmentTokenStore.forTest(
                tempDir,
                regenerate,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new SecureRandom());
    }

    private static String printedToken(ByteArrayOutputStream output) {
        String line = output.toString(StandardCharsets.UTF_8).strip();
        assertThat(line).startsWith(SshEnrollmentTokenStore.TOKEN_OUTPUT_PREFIX);
        return line.substring(SshEnrollmentTokenStore.TOKEN_OUTPUT_PREFIX.length());
    }
}
