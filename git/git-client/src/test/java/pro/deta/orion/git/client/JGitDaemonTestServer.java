package pro.deta.orion.git.client;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.Daemon;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

final class JGitDaemonTestServer implements AutoCloseable {
    private final Path repositoryPath;
    private final Daemon daemon;

    private JGitDaemonTestServer(Path repositoryPath, Daemon daemon) {
        this.repositoryPath = repositoryPath;
        this.daemon = daemon;
    }

    static JGitDaemonTestServer start(Path repositoryPath) throws IOException {
        Path checkedPath = repositoryPath.toAbsolutePath().normalize();
        Daemon daemon = new Daemon(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0));
        JGitDaemonTestServer server = new JGitDaemonTestServer(
                checkedPath, daemon);
        daemon.setRepositoryResolver((client, name) ->
                server.openRepository(name));
        daemon.getService("receive-pack").setEnabled(true);
        daemon.start();
        return server;
    }

    URI repositoryUri() {
        InetSocketAddress address = daemon.getAddress();
        return URI.create("git://127.0.0.1:"
                + address.getPort() + "/test.git");
    }

    GitClientTransport transport() {
        return new SocketTransport();
    }

    private Repository openRepository(String name)
            throws RepositoryNotFoundException {
        if (!"test.git".equals(name)) {
            throw new RepositoryNotFoundException(name);
        }
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(repositoryPath.toFile())
                    .build();
        } catch (IOException error) {
            throw new RepositoryNotFoundException(name, error);
        }
    }

    @Override
    public void close() throws InterruptedException {
        daemon.stopAndWait();
    }

    private static final class SocketTransport implements GitClientTransport {
        @Override
        public GitClientTransportSession open(
                GitClientService service,
                URI remoteUri,
                GitClientOptions options) throws GitClientTransportException {
            Socket socket = new Socket();
            try {
                socket.connect(
                        new InetSocketAddress(
                                remoteUri.getHost(), remoteUri.getPort()),
                        timeoutMillis(options.connectTimeout()));
                socket.setSoTimeout(timeoutMillis(options.readTimeout()));
                sendServiceRequest(socket, service, remoteUri);
                return new SocketSession(socket);
            } catch (IOException error) {
                closeAfterFailure(socket, error);
                throw new GitClientTransportException(
                        GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                        true,
                        "Failed to open JGit test session",
                        error);
            }
        }

        private static void sendServiceRequest(
                Socket socket,
                GitClientService service,
                URI remoteUri) throws IOException {
            String payload = service.command()
                    + " " + remoteUri.getPath()
                    + "\0host=" + remoteUri.getHost()
                    + ":" + remoteUri.getPort()
                    + "\0";
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] header = "%04x".formatted(payloadBytes.length + 4)
                    .getBytes(StandardCharsets.US_ASCII);
            socket.getOutputStream().write(header);
            socket.getOutputStream().write(payloadBytes);
            socket.getOutputStream().flush();
        }

        private static int timeoutMillis(Duration timeout) {
            return Math.toIntExact(Math.min(
                    timeout.toMillis(), Integer.MAX_VALUE));
        }

        private static void closeAfterFailure(
                Socket socket,
                IOException failure) {
            try {
                socket.close();
            } catch (IOException closeError) {
                failure.addSuppressed(closeError);
            }
        }
    }

    private static final class SocketSession
            implements GitClientTransportSession {
        private final Socket socket;
        private final InputStreamBufferedByteInput input;
        private final OutputStreamBufferedByteOutput output;

        private SocketSession(Socket socket) throws IOException {
            this.socket = Objects.requireNonNull(socket, "socket");
            input = new InputStreamBufferedByteInput(socket.getInputStream());
            output = new OutputStreamBufferedByteOutput(
                    socket.getOutputStream());
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
            socket.close();
        }
    }
}
