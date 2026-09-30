package pro.deta.orion.git.client;

import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Local Git repository initialization and protocol transport through Git processes.
 */
public final class GitFileClientTransport implements GitClientTransport {
    public static void openOrInitialize(Path directory, boolean createIfMissing) throws IOException {
        Path repository = directory.toAbsolutePath().normalize();
        if (Files.exists(repository.resolve(".git"))) {
            runGit("-C", repository.toString(), "rev-parse", "--git-dir");
            return;
        }
        if (Files.isRegularFile(repository.resolve("HEAD"))) {
            runGit("--git-dir=" + repository, "rev-parse", "--is-bare-repository");
            return;
        }
        if (!createIfMissing) {
            throw new IOException("Local Git repository does not exist");
        }
        if (Files.exists(repository)) {
            try (var entries = Files.newDirectoryStream(repository)) {
                if (entries.iterator().hasNext()) {
                    throw new IOException("Cannot initialize Git in a nonempty configuration directory");
                }
            }
        }
        runGit("init", "--bare", "--quiet", "--initial-branch=main", repository.toString());
    }

    private static void runGit(String... arguments) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("Local Git repository initialization timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Cannot open or initialize local Git repository");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Local Git repository initialization interrupted", failure);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Override
    public GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(options, "options");
        Path repository = validate(remoteUri);
        Process process = null;
        try {
            process = new ProcessBuilder(service.command(), repository.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return GitTimedTransportSession.wrap(new ProcessSession(process), options);
        } catch (IOException | RuntimeException error) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw new GitClientTransportException(
                    GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                    false,
                    "Failed to open local Git transport",
                    error);
        }
    }

    private static Path validate(URI remoteUri) throws GitClientTransportException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        try {
            if (GitTransportScheme.from(remoteUri) != GitTransportScheme.FILE
                    || remoteUri.getRawAuthority() != null
                    || remoteUri.getRawQuery() != null
                    || remoteUri.getRawFragment() != null) {
                throw unsupported();
            }
            Path path = Path.of(remoteUri).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new GitClientTransportException(
                        GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                        false,
                        "Local Git repository is unavailable");
            }
            return path;
        } catch (IllegalArgumentException error) {
            throw unsupported();
        }
    }

    private static GitClientTransportException unsupported() {
        return new GitClientTransportException(
                GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                false,
                "Local Git transport requires a file URI without extra components");
    }

    private static final class ProcessSession implements GitClientTransportSession {
        private final Process process;
        private final BufferedByteInputV2 input;
        private final OutputStreamBufferedByteOutput output;

        private ProcessSession(Process process) {
            this.process = process;
            input = new BufferedByteInputV2(process.getInputStream());
            output = new OutputStreamBufferedByteOutput(process.getOutputStream());
        }

        @Override
        public BufferedByteInputV2 input() {
            return input;
        }

        @Override
        public BufferedByteOutput output() {
            return output;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                process.getOutputStream().close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                process.getInputStream().close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            process.destroy();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                if (failure == null) {
                    failure = new IOException("Interrupted while closing local Git transport", error);
                } else {
                    failure.addSuppressed(error);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
