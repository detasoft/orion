package pro.deta.orion.git.client;

import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Blocking transport for the native {@code git://} protocol.
 */
public final class GitTcpClientTransport implements GitClientTransport {
    private static final int DEFAULT_PORT = 9418;

    @Override
    public GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(options, "options");
        Remote remote = validate(remoteUri);
        Socket socket = new Socket();
        try {
            socket.connect(
                    new InetSocketAddress(remote.host(), remote.port()),
                    timeoutMillis(options.connectTimeout()));
            socket.setSoTimeout(timeoutMillis(options.readTimeout()));
            GitClientTransportSession session = GitTimedTransportSession.wrap(
                    new SocketSession(socket), options);
            sendServiceRequest(session.output(), service, remote);
            return session;
        } catch (GitClientTransportException error) {
            closeAfterFailure(socket, error);
            throw error;
        } catch (SocketTimeoutException error) {
            closeAfterFailure(socket, error);
            throw new GitClientTransportException(
                    GitClientFailure.Kind.TIMEOUT,
                    true,
                    "Native Git TCP connection timed out",
                    error);
        } catch (IOException error) {
            closeAfterFailure(socket, error);
            throw new GitClientTransportException(
                    GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                    true,
                    "Failed to open native Git TCP session",
                    error);
        }
    }

    private static Remote validate(URI remoteUri)
            throws GitClientTransportException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        if (!"git".equalsIgnoreCase(remoteUri.getScheme())) {
            throw unsupported("Native Git TCP transport requires a git URI");
        }
        if (remoteUri.getHost() == null || remoteUri.getHost().isBlank()) {
            throw unsupported("Native Git TCP URI requires a host");
        }
        if (remoteUri.getRawUserInfo() != null
                || remoteUri.getRawQuery() != null
                || remoteUri.getRawFragment() != null) {
            throw unsupported("Native Git TCP URI contains unsupported components");
        }
        String path = remoteUri.getRawPath();
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw unsupported("Native Git TCP URI requires an absolute repository path");
        }
        int port = remoteUri.getPort() < 0 ? DEFAULT_PORT : remoteUri.getPort();
        return new Remote(remoteUri.getHost(), port, path);
    }

    private static GitClientTransportException unsupported(String message) {
        return new GitClientTransportException(
                GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                false,
                message);
    }

    private static void sendServiceRequest(
            BufferedByteOutput output,
            GitClientService service,
            Remote remote) throws IOException {
        String payload = service.command()
                + " " + remote.path()
                + "\0host=" + remote.host()
                + ":" + remote.port()
                + "\0";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] header = "%04x".formatted(payloadBytes.length + 4)
                .getBytes(StandardCharsets.US_ASCII);
        output.write(header);
        output.write(payloadBytes);
        output.flush();
    }

    private static int timeoutMillis(Duration timeout) {
        return Math.toIntExact(Math.min(timeout.toMillis(), Integer.MAX_VALUE));
    }

    private static void closeAfterFailure(Socket socket, IOException failure) {
        try {
            socket.close();
        } catch (IOException closeError) {
            failure.addSuppressed(closeError);
        }
    }

    private record Remote(String host, int port, String path) {
    }

    private static final class SocketSession implements GitClientTransportSession {
        private final Socket socket;
        private final InputStreamBufferedByteInput input;
        private final OutputStreamBufferedByteOutput output;

        private SocketSession(Socket socket) throws IOException {
            this.socket = socket;
            input = new InputStreamBufferedByteInput(socket.getInputStream());
            output = new OutputStreamBufferedByteOutput(socket.getOutputStream());
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
