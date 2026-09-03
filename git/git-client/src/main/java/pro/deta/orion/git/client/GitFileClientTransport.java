package pro.deta.orion.git.client;

import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Native Git client transport for a local repository through Git's upload-pack process.
 */
public final class GitFileClientTransport implements GitClientTransport {
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
            if (!"file".equalsIgnoreCase(remoteUri.getScheme())
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
        private final InputStreamBufferedByteInput input;
        private final OutputStreamBufferedByteOutput output;

        private ProcessSession(Process process) {
            this.process = process;
            input = new InputStreamBufferedByteInput(process.getInputStream());
            output = new OutputStreamBufferedByteOutput(process.getOutputStream());
        }

        @Override
        public BufferedByteInput input() {
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
