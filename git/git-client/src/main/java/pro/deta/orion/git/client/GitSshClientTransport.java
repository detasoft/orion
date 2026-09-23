package pro.deta.orion.git.client;

import org.apache.sshd.client.SshClient;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.schema.orion.GitProxyBinding;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.orion.GitCredentialKind;

import java.io.CharArrayReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Set;
import java.security.PublicKey;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Blocking Git transport over Apache MINA SSH. Injected clients remain caller-owned;
 * clients with configured trust are created per exchange and closed with their transport session.
 */
public final class GitSshClientTransport implements GitClientTransport {
    private static final int DEFAULT_PORT = 22;

    private final SshClient client;
    private final GitCredentials credentials;
    private final Set<String> knownHosts;

    public GitSshClientTransport(
            SshClient client,
            GitCredentials credentials) {
        this(Objects.requireNonNull(client, "client"), credentials, null);
    }

    /**
     * Creates an SSH transport which rejects unknown and changed server host keys.
     */
    public static GitSshClientTransport strictKnownHosts(
            Set<String> knownHosts,
            GitCredentials credentials) {
        Objects.requireNonNull(knownHosts, "knownHosts");
        return new GitSshClientTransport(null, credentials, knownHosts);
    }

    private GitSshClientTransport(
            SshClient client,
            GitCredentials credentials,
            Set<String> knownHosts) {
        this.client = client;
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.knownHosts = knownHosts == null ? null
                : GitProxyBinding.canonicalKnownHosts(knownHosts);
    }

    @Override
    public GitClientTransportSession open(
            GitClientService service,
            URI remoteUri,
            GitClientOptions options) throws GitClientTransportException {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(options, "options");
        Remote remote = validate(remoteUri);
        credentials.requireKind(GitCredentialKind.NONE, GitCredentialKind.PASSWORD,
                GitCredentialKind.PRIVATE_KEY);
        Attempt attempt = newAttempt();
        ClientSession session = null;
        ClientChannel channel = null;
        try {
            ConnectFuture connect = attempt.client().connect(
                    remote.user(), remote.host(), remote.port());
            await(connect, options.connectTimeout());
            session = connect.verify().getSession();
            try {
                authenticate(session, options.connectTimeout());
            } catch (AuthenticationTimeoutException error) {
                throw new SshTimeoutException(error);
            } catch (IOException error) {
                if (attempt.verifier().wasRejected()) {
                    throw new HostKeyRejectedException(remote, attempt.verifier().rejectedKey.get(), error);
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
        } catch (HostKeyRejectedException error) {
            closeAfterFailure(channel, session, error);
            stopAfterFailure(attempt, error);
            throw new GitClientTransportException(
                    GitClientFailure.Kind.VERIFICATION_FAILED,
                    false,
                    "Git SSH server host key was rejected",
                    error);
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
                    attempt.verifier().wasRejected()
                            ? new HostKeyRejectedException(remote, attempt.verifier().rejectedKey.get(), error)
                            : error);
        }
    }

    private void authenticate(ClientSession session, Duration timeout) throws IOException {
        char[] secret = credentials.copyCharacters();
        try {
            switch (credentials.kind()) {
                case NONE -> { }
                case PASSWORD -> session.addPasswordIdentity(new String(secret));
                case PRIVATE_KEY -> session.addPublicKeyIdentity(parseKeyPair(secret));
                default -> throw new IllegalStateException("Unsupported SSH credential kind");
            }
        } finally {
            Arrays.fill(secret, '\0');
        }
        var authentication = session.auth();
        if (!authentication.await(timeout)) {
            authentication.cancel();
            throw new AuthenticationTimeoutException();
        }
        authentication.verify();
    }

    private static KeyPair parseKeyPair(char[] secret) throws IOException {
        try (PEMParser parser = new PEMParser(new CharArrayReader(secret))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair keyPair) {
                return converter.getKeyPair(keyPair);
            }
            if (parsed instanceof PrivateKeyInfo keyInfo) {
                PrivateKey privateKey = converter.getPrivateKey(keyInfo);
                if (privateKey instanceof RSAPrivateCrtKey rsa) {
                    RSAPublicKeySpec publicSpec = new RSAPublicKeySpec(
                            rsa.getModulus(), rsa.getPublicExponent());
                    return new KeyPair(KeyFactory.getInstance("RSA").generatePublic(publicSpec), privateKey);
                }
            }
        } catch (Exception error) {
            throw new IOException("Invalid SSH private key");
        }
        throw new IOException("Unsupported SSH private key");
    }

    private static final class AuthenticationTimeoutException extends IOException {
    }

    private Attempt newAttempt() {
        if (knownHosts == null) {
            return new Attempt(client, TrackingVerifier.none(), null);
        }
        SshClient strictClient = SshClient.setUpDefaultClient();
        TrackingVerifier verifier = new TrackingVerifier(
                (session, address, key) -> knownHosts.contains(PublicKeyEntry.toString(key)));
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
        if (GitTransportScheme.from(remoteUri) != GitTransportScheme.SSH) {
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

    private static final class TrackingVerifier implements ServerKeyVerifier {
        private final ServerKeyVerifier delegate;
        private final AtomicReference<PublicKey> rejectedKey = new AtomicReference<>();

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
                rejectedKey.compareAndSet(null, serverKey);
            }
            return accepted;
        }

        private boolean wasRejected() {
            return rejectedKey.get() != null;
        }
    }

    private static final class AuthenticationException extends IOException {
        private AuthenticationException(IOException cause) {
            super(cause);
        }
    }

    /**
     * The key rejected by the configured verifier, bound to the requested host and port.
     * This evidence does not authorize trusting the key or bypassing configured trust.
     */
    public static final class HostKeyRejectedException extends IOException {
        private final String host;
        private final int port;
        private final PublicKey serverKey;

        private HostKeyRejectedException(Remote remote, PublicKey serverKey, Throwable cause) {
            super("Git SSH server host key was rejected", cause);
            this.host = remote.host();
            this.port = remote.port();
            this.serverKey = Objects.requireNonNull(serverKey, "serverKey");
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public PublicKey serverKey() {
            return serverKey;
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
        private final BufferedByteInputV2 input;
        private final OutputStreamBufferedByteOutput output;

        private SshSession(
                ClientSession session,
                ClientChannel channel,
                SshClient ownedClient) {
            this.session = session;
            this.channel = channel;
            this.ownedClient = ownedClient;
            input = new BufferedByteInputV2(channel.getInvertedOut());
            output = new OutputStreamBufferedByteOutput(channel.getInvertedIn());
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
