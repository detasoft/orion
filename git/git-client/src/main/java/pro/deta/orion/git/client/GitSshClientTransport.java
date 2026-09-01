package pro.deta.orion.git.client;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking Git transport over an externally configured Apache MINA SSH client.
 * The caller owns the client lifecycle and its server host-key verification policy.
 */
public final class GitSshClientTransport implements GitClientTransport, AutoCloseable {
    private static final int DEFAULT_PORT = 22;

    private final SshClient client;
    private final GitSshSessionAuthenticator authenticator;
    private final Path knownHosts;

    public GitSshClientTransport(
            SshClient client,
            GitSshSessionAuthenticator authenticator) {
        this(Objects.requireNonNull(client, "client"), authenticator, null);
    }

    /**
     * Creates an SSH transport which rejects unknown and changed server host keys.
     */
    public static GitSshClientTransport strictKnownHosts(
            Path knownHosts,
            GitSshSessionAuthenticator authenticator) {
        Objects.requireNonNull(knownHosts, "knownHosts");
        return new GitSshClientTransport(null, authenticator, knownHosts);
    }

    private GitSshClientTransport(
            SshClient client,
            GitSshSessionAuthenticator authenticator,
            Path knownHosts) {
        this.client = client;
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.knownHosts = knownHosts;
    }

    @Override
    public GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(options, "options");
        Remote remote = validate(remoteUri);
        Attempt attempt = newAttempt();
        ClientSession session = null;
        ClientChannel channel = null;
        try {
            ConnectFuture connect = attempt.client().connect(
                    remote.user(), remote.host(), remote.port());
            await(connect, options.connectTimeout());
            session = connect.verify().getSession();
            try {
                authenticator.authenticate(session, options.connectTimeout());
            } catch (GitSshAuthenticationTimeoutException error) {
                throw new SshTimeoutException(error);
            } catch (IOException error) {
                if (attempt.verifier().wasRejected()) {
                    throw new VerificationException(error);
                }
                throw new AuthenticationException(error);
            }
            channel = session.createExecChannel(
                    service.command() + " " + shellQuote(remote.path()));
            channel.setErr(OutputStream.nullOutputStream());
            OpenFuture open = channel.open();
            await(open, options.connectTimeout());
            open.verify();
            return GitTimedTransportSession.wrap(
                    new SshSession(session, channel, attempt.ownedClient()), options);
        } catch (AuthenticationException error) {
            closeAfterFailure(channel, session, error);
            stopAfterFailure(attempt, error);
            throw new GitClientTransportException(
                    GitClientFailure.Kind.AUTHENTICATION_FAILED,
                    false,
                    "Git SSH authentication failed",
                    error.getCause());
        } catch (VerificationException error) {
            closeAfterFailure(channel, session, error);
            stopAfterFailure(attempt, error);
            throw new GitClientTransportException(
                    GitClientFailure.Kind.VERIFICATION_FAILED,
                    false,
                    "Git SSH server host key was rejected",
                    error.getCause());
        } catch (SshTimeoutException error) {
            closeAfterFailure(channel, session, error);
            stopAfterFailure(attempt, error);
            throw new GitClientTransportException(
                    GitClientFailure.Kind.TIMEOUT,
                    true,
                    "Git SSH connection timed out",
                    error);
        } catch (IOException | RuntimeException error) {
            closeAfterFailure(channel, session, error);
            stopAfterFailure(attempt, error);
            throw new GitClientTransportException(
                    attempt.verifier().wasRejected()
                            ? GitClientFailure.Kind.VERIFICATION_FAILED
                            : GitClientFailure.Kind.TRANSPORT_UNAVAILABLE,
                    !attempt.verifier().wasRejected(),
                    attempt.verifier().wasRejected()
                            ? "Git SSH server host key was rejected"
                            : "Failed to open Git SSH session",
                    error);
        }
    }

    private Attempt newAttempt() {
        if (knownHosts == null) {
            return new Attempt(client, TrackingVerifier.none(), null);
        }
        SshClient strictClient = SshClient.setUpDefaultClient();
        TrackingVerifier verifier = new TrackingVerifier(
                new DefaultKnownHostsServerKeyVerifier(
                        RejectAllServerKeyVerifier.INSTANCE, true, knownHosts));
        strictClient.setServerKeyVerifier(verifier);
        strictClient.start();
        return new Attempt(strictClient, verifier, strictClient);
    }

    private static void stopAfterFailure(Attempt attempt, Throwable failure) {
        if (attempt.ownedClient() != null) {
            try {
                attempt.ownedClient().stop();
            } catch (RuntimeException stopError) {
                failure.addSuppressed(stopError);
            }
        }
    }

    private static void await(
            org.apache.sshd.common.future.WaitableFuture future,
            Duration timeout) throws IOException, SshTimeoutException {
        if (!future.await(timeout)) {
            if (future instanceof org.apache.sshd.common.future.Cancellable cancellable) {
                cancellable.cancel();
            }
            throw new SshTimeoutException();
        }
    }

    private static Remote validate(URI remoteUri)
            throws GitClientTransportException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        if (!"ssh".equalsIgnoreCase(remoteUri.getScheme())) {
            throw unsupported("Git SSH transport requires an ssh URI");
        }
        if (remoteUri.getHost() == null || remoteUri.getHost().isBlank()) {
            throw unsupported("Git SSH URI requires a host");
        }
        String user = remoteUri.getUserInfo();
        if (user == null || user.isBlank() || user.indexOf(':') >= 0) {
            throw unsupported("Git SSH URI requires a username without a password");
        }
        if (remoteUri.getRawQuery() != null || remoteUri.getRawFragment() != null) {
            throw unsupported("Git SSH URI contains unsupported components");
        }
        String path = remoteUri.getPath();
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw unsupported("Git SSH URI requires a valid repository path");
        }
        int port = remoteUri.getPort() < 0 ? DEFAULT_PORT : remoteUri.getPort();
        return new Remote(user, remoteUri.getHost(), port, path);
    }

    private static GitClientTransportException unsupported(String message) {
        return new GitClientTransportException(
                GitClientFailure.Kind.PROTOCOL_UNSUPPORTED,
                false,
                message);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void closeAfterFailure(
            ClientChannel channel,
            ClientSession session,
            Throwable failure) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException | RuntimeException closeError) {
                failure.addSuppressed(closeError);
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (IOException | RuntimeException closeError) {
                failure.addSuppressed(closeError);
            }
        }
    }

    private record Remote(String user, String host, int port, String path) {
    }

    private record Attempt(
            SshClient client,
            TrackingVerifier verifier,
            SshClient ownedClient) {
    }

    @Override
    public void close() {
    }

    private static final class TrackingVerifier implements ServerKeyVerifier {
        private final ServerKeyVerifier delegate;
        private final AtomicBoolean rejected = new AtomicBoolean();

        private TrackingVerifier(ServerKeyVerifier delegate) {
            this.delegate = delegate;
        }

        private static TrackingVerifier none() {
            return new TrackingVerifier((session, address, key) -> true);
        }

        @Override
        public boolean verifyServerKey(
                ClientSession session,
                SocketAddress remoteAddress,
                PublicKey serverKey) {
            boolean accepted = delegate.verifyServerKey(
                    session, remoteAddress, serverKey);
            if (!accepted) {
                rejected.set(true);
            }
            return accepted;
        }

        private boolean wasRejected() {
            return rejected.get();
        }
    }

    private static final class AuthenticationException extends IOException {
        private AuthenticationException(IOException cause) {
            super(cause);
        }
    }

    private static final class VerificationException extends IOException {
        private VerificationException(IOException cause) {
            super(cause);
        }
    }

    private static final class SshTimeoutException extends IOException {
        private SshTimeoutException() {
        }

        private SshTimeoutException(IOException cause) {
            super(cause);
        }
    }

    private static final class SshSession implements GitClientTransportSession {
        private final ClientSession session;
        private final ClientChannel channel;
        private final SshClient ownedClient;
        private final InputStreamBufferedByteInput input;
        private final OutputStreamBufferedByteOutput output;

        private SshSession(
                ClientSession session,
                ClientChannel channel,
                SshClient ownedClient) {
            this.session = session;
            this.channel = channel;
            this.ownedClient = ownedClient;
            input = new InputStreamBufferedByteInput(channel.getInvertedOut());
            output = new OutputStreamBufferedByteOutput(channel.getInvertedIn());
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
                channel.close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                session.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            if (ownedClient != null) {
                ownedClient.stop();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
