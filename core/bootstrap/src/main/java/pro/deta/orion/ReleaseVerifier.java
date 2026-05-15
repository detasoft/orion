package pro.deta.orion;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class ReleaseVerifier {
    private final CommandRunner commandRunner;
    private final Downloader downloader;

    ReleaseVerifier(CommandRunner commandRunner, Downloader downloader) {
        this.commandRunner = commandRunner;
        this.downloader = downloader;
    }

    static ReleaseVerifier systemDefault() {
        return new ReleaseVerifier(new ProcessCommandRunner(), new HttpClientDownloader());
    }

    int verify(AppOptions options, PrintStream output, PrintStream errors) {
        try {
            Path artifact = artifact(options);
            verifyRequiredInputs(options, artifact);
            Path temporaryDirectory = Files.createTempDirectory("orion-verify-");
            try {
                Path publicKey = resolvePublicKey(options, temporaryDirectory);
                Path signature = resolveSignature(options, artifact, temporaryDirectory);
                verifyPublicKeyFingerprint(options, publicKey);
                verifyDetachedSignature(options, publicKey, signature, artifact, temporaryDirectory);
            } finally {
                deleteRecursively(temporaryDirectory);
            }

            output.println("Signature verified for " + artifact);
            return 0;
        } catch (VerificationFailure e) {
            errors.println(e.getMessage());
            return e.exitCode;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.println("Signature verification was interrupted");
            return 1;
        } catch (IOException e) {
            errors.println("Signature verification failed: " + e.getMessage());
            return 1;
        }
    }

    private void verifyRequiredInputs(AppOptions options, Path artifact) throws VerificationFailure {
        if (options.normalizedReleaseKeyFingerprint().isBlank()) {
            throw new VerificationFailure(
                    2,
                    "release key fingerprint is required; pass --fingerprint or set ORION_RELEASE_KEY_FINGERPRINT"
            );
        }
        if (!Files.isRegularFile(artifact)) {
            throw new VerificationFailure(2, "Release artifact not found: " + artifact);
        }
    }

    private Path resolvePublicKey(AppOptions options, Path temporaryDirectory)
            throws IOException, InterruptedException, VerificationFailure {
        if (options.releasePublicKey() != null) {
            if (!Files.isRegularFile(options.releasePublicKey())) {
                throw new VerificationFailure(2, "Release public key file not found: " + options.releasePublicKey());
            }
            return options.releasePublicKey();
        }

        Path publicKey = temporaryDirectory.resolve("release.asc");
        downloader.download(options.releasePublicKeyUrl(), publicKey);
        return publicKey;
    }

    private Path resolveSignature(AppOptions options, Path artifact, Path temporaryDirectory)
            throws IOException, InterruptedException, VerificationFailure {
        if (options.releaseSignature() != null) {
            if (!Files.isRegularFile(options.releaseSignature())) {
                throw new VerificationFailure(2, "Detached signature file not found: " + options.releaseSignature());
            }
            return options.releaseSignature();
        }

        Path siblingSignature = Path.of(artifact + ".asc");
        if (Files.isRegularFile(siblingSignature)) {
            return siblingSignature;
        }

        if (options.releaseSignatureUrl() != null) {
            Path signature = temporaryDirectory.resolve("release.asc.sig");
            downloader.download(options.releaseSignatureUrl(), signature);
            return signature;
        }

        throw new VerificationFailure(
                2,
                "Detached signature not found. Pass --signature, set ORION_RELEASE_SIGNATURE, "
                        + "set --signature-url, or place " + siblingSignature + " next to the artifact."
        );
    }

    private void verifyPublicKeyFingerprint(AppOptions options, Path publicKey)
            throws IOException, InterruptedException, VerificationFailure {
        CommandResult result = commandRunner.run(List.of(
                options.gpgCommand(),
                "--batch",
                "--quiet",
                "--with-colons",
                "--import-options",
                "show-only",
                "--import",
                publicKey.toString()
        ));
        if (result.exitCode() != 0) {
            throw new VerificationFailure(1, "Cannot read release public key fingerprint" + commandOutput(result));
        }

        String actualFingerprint = parseFingerprint(result.output());
        if (actualFingerprint.isBlank()) {
            throw new VerificationFailure(1, "Cannot read release public key fingerprint");
        }
        if (!actualFingerprint.equals(options.normalizedReleaseKeyFingerprint())) {
            throw new VerificationFailure(
                    1,
                    "Release public key fingerprint mismatch\nExpected: "
                            + options.normalizedReleaseKeyFingerprint()
                            + "\nActual:   "
                            + actualFingerprint
            );
        }
    }

    private void verifyDetachedSignature(
            AppOptions options,
            Path publicKey,
            Path signature,
            Path artifact,
            Path temporaryDirectory
    ) throws IOException, InterruptedException, VerificationFailure {
        Path gnupgHome = temporaryDirectory.resolve("gnupg");
        Files.createDirectory(gnupgHome);
        setOwnerOnlyPermissions(gnupgHome);

        CommandResult importResult = commandRunner.run(List.of(
                options.gpgCommand(),
                "--batch",
                "--quiet",
                "--homedir",
                gnupgHome.toString(),
                "--import",
                publicKey.toString()
        ));
        if (importResult.exitCode() != 0) {
            throw new VerificationFailure(1, "Cannot import release public key" + commandOutput(importResult));
        }

        CommandResult verifyResult = commandRunner.run(List.of(
                options.gpgCommand(),
                "--batch",
                "--homedir",
                gnupgHome.toString(),
                "--verify",
                signature.toString(),
                artifact.toString()
        ));
        if (verifyResult.exitCode() != 0) {
            throw new VerificationFailure(
                    1,
                    "Signature verification failed for " + artifact + commandOutput(verifyResult)
            );
        }
    }

    private static Path artifact(AppOptions options) {
        if (options.verificationArtifact() != null) {
            return options.verificationArtifact();
        }

        return currentArtifactPath();
    }

    private static String parseFingerprint(String output) {
        for (String line : output.split("\\R")) {
            if (!line.startsWith("fpr:")) {
                continue;
            }

            String[] fields = line.split(":", -1);
            if (fields.length > 9) {
                return AppOptions.normalizeFingerprint(fields[9]);
            }
        }

        return "";
    }

    private static String commandOutput(CommandResult result) {
        if (result.output().isBlank()) {
            return "";
        }
        return "\n" + result.output().strip();
    }

    private static void setOwnerOnlyPermissions(Path directory) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems do not need this for local verification.
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path currentArtifactPath() {
        try {
            return Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate current Orion artifact", e);
        }
    }

    interface CommandRunner {
        CommandResult run(List<String> command) throws IOException, InterruptedException;
    }

    interface Downloader {
        void download(URI uri, Path destination) throws IOException, InterruptedException;
    }

    record CommandResult(int exitCode, String output) {
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command) throws IOException, InterruptedException {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output);
        }
    }

    private static final class HttpClientDownloader implements Downloader {
        private final HttpClient client = HttpClient.newHttpClient();

        @Override
        public void download(URI uri, Path destination) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(destination));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Cannot download " + uri + ": HTTP " + response.statusCode());
            }
        }
    }

    private static final class VerificationFailure extends Exception {
        private final int exitCode;

        private VerificationFailure(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }
    }
}
