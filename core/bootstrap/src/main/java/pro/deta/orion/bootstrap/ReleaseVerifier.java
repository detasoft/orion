package pro.deta.orion.bootstrap;

import pro.deta.orion.App;

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
import java.util.Map;
import java.util.stream.Stream;

public final class ReleaseVerifier implements OrionCli.Verifier {
    private final CommandRunner commandRunner;
    private final Downloader downloader;

    ReleaseVerifier(CommandRunner commandRunner, Downloader downloader) {
        this.commandRunner = commandRunner;
        this.downloader = downloader;
    }

    public static ReleaseVerifier systemDefault() {
        return new ReleaseVerifier(new ProcessCommandRunner(), new HttpClientDownloader());
    }

    @Override
    public int verify(String[] arguments, PrintStream output, PrintStream errors) {
        ReleaseVerificationOptions options;
        try {
            options = ReleaseVerificationOptions.parse(arguments, System.getenv(), currentArtifactPath());
        } catch (IllegalArgumentException e) {
            errors.println(e.getMessage());
            printUsage(errors);
            return 2;
        }

        return verify(options, output, errors);
    }

    int verify(ReleaseVerificationOptions options, PrintStream output, PrintStream errors) {
        if (options.helpRequested()) {
            printUsage(output);
            return 0;
        }

        try {
            verifyRequiredInputs(options);
            Path temporaryDirectory = Files.createTempDirectory("orion-verify-");
            try {
                Path publicKey = resolvePublicKey(options, temporaryDirectory);
                Path signature = resolveSignature(options, temporaryDirectory);
                verifyPublicKeyFingerprint(options, publicKey);
                verifyDetachedSignature(options, publicKey, signature, temporaryDirectory);
            } finally {
                deleteRecursively(temporaryDirectory);
            }

            output.println("Signature verified for " + options.artifact());
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

    private void verifyRequiredInputs(ReleaseVerificationOptions options) throws VerificationFailure {
        if (options.normalizedFingerprint().isBlank()) {
            throw new VerificationFailure(
                    2,
                    "release key fingerprint is required; pass --fingerprint or set ORION_RELEASE_KEY_FINGERPRINT"
            );
        }
        if (!Files.isRegularFile(options.artifact())) {
            throw new VerificationFailure(2, "Release artifact not found: " + options.artifact());
        }
    }

    private Path resolvePublicKey(ReleaseVerificationOptions options, Path temporaryDirectory)
            throws IOException, InterruptedException, VerificationFailure {
        if (options.publicKey() != null) {
            if (!Files.isRegularFile(options.publicKey())) {
                throw new VerificationFailure(2, "Release public key file not found: " + options.publicKey());
            }
            return options.publicKey();
        }

        Path publicKey = temporaryDirectory.resolve("release.asc");
        downloader.download(options.publicKeyUrl(), publicKey);
        return publicKey;
    }

    private Path resolveSignature(ReleaseVerificationOptions options, Path temporaryDirectory)
            throws IOException, InterruptedException, VerificationFailure {
        if (options.signature() != null) {
            if (!Files.isRegularFile(options.signature())) {
                throw new VerificationFailure(2, "Detached signature file not found: " + options.signature());
            }
            return options.signature();
        }

        Path siblingSignature = Path.of(options.artifact().toString() + ".asc");
        if (Files.isRegularFile(siblingSignature)) {
            return siblingSignature;
        }

        if (options.signatureUrl() != null) {
            Path signature = temporaryDirectory.resolve("release.asc.sig");
            downloader.download(options.signatureUrl(), signature);
            return signature;
        }

        throw new VerificationFailure(
                2,
                "Detached signature not found. Pass --signature, set ORION_RELEASE_SIGNATURE, "
                        + "set --signature-url, or place " + siblingSignature + " next to the artifact."
        );
    }

    private void verifyPublicKeyFingerprint(ReleaseVerificationOptions options, Path publicKey)
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
        if (!actualFingerprint.equals(options.normalizedFingerprint())) {
            throw new VerificationFailure(
                    1,
                    "Release public key fingerprint mismatch\nExpected: "
                            + options.normalizedFingerprint()
                            + "\nActual:   "
                            + actualFingerprint
            );
        }
    }

    private void verifyDetachedSignature(
            ReleaseVerificationOptions options,
            Path publicKey,
            Path signature,
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
                options.artifact().toString()
        ));
        if (verifyResult.exitCode() != 0) {
            throw new VerificationFailure(
                    1,
                    "Signature verification failed for " + options.artifact() + commandOutput(verifyResult)
            );
        }
    }

    private static String parseFingerprint(String output) {
        for (String line : output.split("\\R")) {
            if (!line.startsWith("fpr:")) {
                continue;
            }

            String[] fields = line.split(":", -1);
            if (fields.length > 9) {
                return ReleaseVerificationOptions.normalizeFingerprint(fields[9]);
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

    private static void printUsage(PrintStream stream) {
        stream.println("Usage: orion verify [options]");
        stream.println("  --fingerprint VALUE   expected release signing key fingerprint");
        stream.println("  --key PATH            local ASCII-armored release public key");
        stream.println("  --key-url URL         release public key URL");
        stream.println("  --signature PATH      local detached signature");
        stream.println("  --signature-url URL   detached signature URL");
        stream.println("  --artifact PATH       artifact to verify, defaults to the current executable jar");
        stream.println("  --gpg PATH            gpg executable, defaults to gpg");
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
