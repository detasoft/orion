package pro.deta.orion;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReleaseVerifierTest {
    private static final String ARTIFACT_CONTENT = "Orion release verification test artifact\n";

    @TempDir
    private static Path signingDirectory;
    private static Path signingHome;
    private static String expectedFingerprint;
    private static String publicKeyBundle;
    private static boolean gpgAvailable;

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void createSignedArtifacts() throws Exception {
        try {
            gpgAvailable = runCommand(List.of("gpg", "--version")).exitCode() == 0;
        } catch (IOException e) {
            return;
        }
        if (!gpgAvailable) {
            return;
        }

        signingHome = Files.createDirectory(signingDirectory.resolve("gnupg"));
        if (Files.getFileStore(signingHome).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(signingHome, PosixFilePermissions.fromString("rwx------"));
        }
        runGpg("--quick-generate-key", "Orion Release Test <release@example.invalid>", "ed25519", "cert,sign", "0");
        expectedFingerprint = fingerprints("release@example.invalid").getFirst();
        runGpg("--quick-add-key", expectedFingerprint, "ed25519", "sign", "0");
        String signingSubkey = fingerprints("release@example.invalid").get(1);
        runGpg("--quick-generate-key", "Other Release Test <other@example.invalid>", "ed25519", "sign", "0");
        String otherFingerprint = fingerprints("other@example.invalid").getFirst();
        publicKeyBundle = runGpg("--armor", "--export", expectedFingerprint)
                + runGpg("--armor", "--export", otherFingerprint);
        Path artifact = signingDirectory.resolve("orion.jar");
        Files.writeString(artifact, ARTIFACT_CONTENT);
        signArtifact(artifact, "primary", expectedFingerprint);
        signArtifact(artifact, "subkey", signingSubkey);
        signArtifact(artifact, "other", otherFingerprint);
    }

    @AfterAll
    static void stopSigningAgent() throws Exception {
        if (signingHome != null) {
            ReleaseVerifier.CommandResult result = runCommand(List.of(
                    "gpgconf", "--homedir", signingHome.toString(), "--kill", "gpg-agent"));
            assertEquals(0, result.exitCode(), result.output());
        }
    }

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

    @ParameterizedTest
    @ValueSource(strings = {
            "[GNUPG:] VALIDSIG ABCD1234 2026-09-29 1 0 4 0 22 8 00 ABCD1234",
            "[GNUPG:] VALIDSIG ABCD1234 2026-09-29 1 0 4 0 22 8 00",
            "[GNUPG:] VALIDSIG DEADBEEF 2026-09-29 1 0 4 0 22 8 00 ABCD1234",
            "[GNUPG:] FUTURE_STATUS ignored\n[GNUPG:] VALIDSIG ABCD1234 2026-09-29 1 0 4 0 22 8 00 ABCD1234 extra"
    })
    void verifiesArtifactWithMatchingPublicKeyFingerprint(String status) throws Exception {
        RecordingCommandRunner commands = new RecordingCommandRunner();
        commands.verificationStatus = status;
        verifyRecordedSignature(commands, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "[GNUPG:] GOODSIG ABCD1234 Release Test",
            "[GNUPG:] VALIDSIG ABCD1234",
            "[GNUPG:] VALIDSIG ABCD1234 2026-09-29 1 0 4 0 22 8 00 DEADBEEF"
    })
    void rejectsUnconfirmedSignerDespiteMatchingDiagnosticOutput(String status) throws Exception {
        RecordingCommandRunner commands = new RecordingCommandRunner();
        commands.verificationStatus = status;
        commands.verificationOutput = "[GNUPG:] VALIDSIG ABCD1234 2026-09-29 1 0 4 0 22 8 00 ABCD1234";
        verifyRecordedSignature(commands, 1);
    }

    @Test
    void rejectsGpgFailureEvenWithMatchingValidSignatureStatus() throws Exception {
        RecordingCommandRunner commands = new RecordingCommandRunner();
        commands.verificationStatus = "[GNUPG:] VALIDSIG ABCD1234 2026-09-29 1 0 4 0 22 8 00 ABCD1234";
        commands.verificationExitCode = 1;
        verifyRecordedSignature(commands, 1);
    }

    private void verifyRecordedSignature(RecordingCommandRunner commands, int expectedExitCode) throws Exception {
        Path artifact = tempDir.resolve("orion.jar");
        Path key = tempDir.resolve("release.asc");
        Path signature = tempDir.resolve("orion.jar.asc");
        Files.writeString(artifact, "jar");
        Files.writeString(key, "public key");
        Files.writeString(signature, "signature");
        commands.outputForFingerprint = "fpr:::::::::ABCD1234:\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

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
                new PrintStream(errors, true, StandardCharsets.UTF_8)
        );

        assertEquals(expectedExitCode, exitCode, errors.toString(StandardCharsets.UTF_8));
        if (expectedExitCode == 0) {
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Signature verified"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
        } else {
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Signature"));
        }
    }

    @Test
    void rejectsSignatureByAnotherPrimaryKeyInTheBundle() throws Exception {
        verifyRealSignature("other", ARTIFACT_CONTENT, 1);
    }

    @Test
    void acceptsSignatureByExpectedPrimaryKey() throws Exception {
        verifyRealSignature("primary", ARTIFACT_CONTENT, 0);
    }

    @Test
    void acceptsSignatureByExpectedSigningSubkey() throws Exception {
        verifyRealSignature("subkey", ARTIFACT_CONTENT, 0);
    }

    @Test
    void rejectsArtifactChangedAfterSigning() throws Exception {
        verifyRealSignature("primary", ARTIFACT_CONTENT + "changed", 1);
    }

    private void verifyRealSignature(String signer, String artifactContent, int expectedExitCode) throws Exception {
        assumeTrue(gpgAvailable, "gpg is not available");
        Path artifact = tempDir.resolve("orion.jar");
        Path key = tempDir.resolve("release.asc");
        Files.writeString(artifact, artifactContent);
        Files.writeString(key, publicKeyBundle);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        ReleaseVerifier verifier = new ReleaseVerifier(command -> {
            List<String> isolatedCommand = new ArrayList<>(command);
            isolatedCommand.addAll(1, List.of("--no-options", "--no-autostart",
                    "--homedir", signingHome.toString()));
            return runCommand(isolatedCommand);
        }, (uri, destination) -> {
            throw new AssertionError("download should not be attempted");
        });
        int exitCode = verifier.verify(
                AppOptions.parse(new String[]{
                        "verify",
                        "--artifact", artifact.toString(),
                        "--key", key.toString(),
                        "--signature", signingDirectory.resolve(signer + ".asc").toString(),
                        "--fingerprint", expectedFingerprint
                }, Map.of()),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));

        assertEquals(expectedExitCode, exitCode, errors.toString(StandardCharsets.UTF_8));
        if (expectedExitCode == 0) {
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Signature verified"));
            assertEquals("", errors.toString(StandardCharsets.UTF_8));
        } else {
            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Signature"));
        }
    }

    private static void signArtifact(Path artifact, String name, String fingerprint) throws Exception {
        runGpg("--armor", "--local-user", fingerprint + "!", "--detach-sign",
                "--output", signingDirectory.resolve(name + ".asc").toString(), artifact.toString());
    }

    private static List<String> fingerprints(String selector) throws Exception {
        List<String> fingerprints = new ArrayList<>();
        for (String line : runGpg("--with-colons", "--list-keys", selector).split("\\R")) {
            if (line.startsWith("fpr:")) {
                fingerprints.add(line.split(":", -1)[9]);
            }
        }
        return fingerprints;
    }

    private static String runGpg(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "gpg", "--no-options", "--homedir", signingHome.toString(), "--batch",
                "--pinentry-mode", "loopback", "--passphrase", ""));
        command.addAll(List.of(arguments));
        ReleaseVerifier.CommandResult result = runCommand(command);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }

    private static ReleaseVerifier.CommandResult runCommand(List<String> command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Command timed out: " + command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ReleaseVerifier.CommandResult(process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static PrintStream newPrintStream() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static class RecordingCommandRunner implements ReleaseVerifier.CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private String outputForFingerprint = "";
        private String verificationStatus = "";
        private String verificationOutput = "";
        private int verificationExitCode;

        @Override
        public ReleaseVerifier.CommandResult run(List<String> command) throws IOException {
            commands.add(command);
            if (command.contains("show-only")) {
                return new ReleaseVerifier.CommandResult(0, outputForFingerprint);
            }
            if (command.contains("--verify")) {
                int statusFileOption = command.indexOf("--status-file");
                if (statusFileOption >= 0) {
                    Files.writeString(Path.of(command.get(statusFileOption + 1)), verificationStatus);
                }
                return new ReleaseVerifier.CommandResult(verificationExitCode, verificationOutput);
            }
            return new ReleaseVerifier.CommandResult(0, "");
        }
    }
}
