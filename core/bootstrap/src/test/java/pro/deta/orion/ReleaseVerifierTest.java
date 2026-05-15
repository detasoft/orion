package pro.deta.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVerifierTest {

    @TempDir
    private Path tempDir;

    @Test
    void failsClosedWhenExpectedFingerprintIsMissing() throws Exception {
        Path artifact = tempDir.resolve("orion.jar");
        Files.writeString(artifact, "jar");
        RecordingCommandRunner commands = new RecordingCommandRunner();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = new ReleaseVerifier(commands, (uri, destination) -> {
            throw new AssertionError("download should not be attempted");
        }).verify(
                AppOptions.parse(new String[]{"verify", "--artifact", artifact.toString()}, Map.of()),
                newPrintStream(),
                new PrintStream(errors, true, StandardCharsets.UTF_8)
        );

        assertEquals(2, exitCode);
        assertTrue(commands.commands.isEmpty());
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("release key fingerprint is required"));
    }

    @Test
    void verifiesArtifactWithMatchingPublicKeyFingerprint() throws Exception {
        Path artifact = tempDir.resolve("orion.jar");
        Path key = tempDir.resolve("release.asc");
        Path signature = tempDir.resolve("orion.jar.asc");
        Files.writeString(artifact, "jar");
        Files.writeString(key, "public key");
        Files.writeString(signature, "signature");
        RecordingCommandRunner commands = new RecordingCommandRunner();
        commands.outputForFingerprint = "fpr:::::::::ABCD1234:\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = new ReleaseVerifier(commands, (uri, destination) -> {
            throw new AssertionError("download should not be attempted");
        }).verify(
                AppOptions.parse(
                        new String[]{
                                "verify",
                                "--artifact", artifact.toString(),
                                "--key", key.toString(),
                                "--signature", signature.toString(),
                                "--fingerprint", "AB CD 12 34"
                        },
                        Map.of()
                ),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                newPrintStream()
        );

        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Signature verified"));
    }

    private static PrintStream newPrintStream() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static class RecordingCommandRunner implements ReleaseVerifier.CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private String outputForFingerprint = "";

        @Override
        public ReleaseVerifier.CommandResult run(List<String> command) throws IOException {
            commands.add(command);
            if (command.contains("show-only")) {
                return new ReleaseVerifier.CommandResult(0, outputForFingerprint);
            }
            return new ReleaseVerifier.CommandResult(0, "");
        }
    }
}
