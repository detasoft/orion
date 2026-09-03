package pro.deta.orion.provisioning;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.PublicKey;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class MinaSshOperation implements AutoCloseable {
    private static final int OUTPUT_LIMIT = 16 * 1024;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(
            new WatchdogThreadFactory());

    private final SshClient client;
    private final ClientSession session;
    private final ProvisioningOptions options;
    private final AtomicBoolean timedOut;
    private final ScheduledFuture<?> watchdog;
    private boolean closed;

    private MinaSshOperation(
            SshClient client,
            ClientSession session,
            ProvisioningOptions options,
            AtomicBoolean timedOut,
            ScheduledFuture<?> watchdog) {
        this.client = client;
        this.session = session;
        this.options = options;
        this.timedOut = timedOut;
        this.watchdog = watchdog;
    }

    public static MinaSshOperation open(
            SshEndpoint endpoint,
            SshCredentials credentials,
            ProvisioningOptions options) throws ProvisioningException {
        return open(endpoint, credentials, options, SshClient::setUpDefaultClient);
    }

    @TestOnly
    static MinaSshOperation open(
            SshEndpoint endpoint,
            SshCredentials credentials,
            ProvisioningOptions options,
            Supplier<SshClient> clientFactory) throws ProvisioningException {
        if (endpoint == null || credentials == null || options == null) {
            throw new IllegalArgumentException("SSH operation arguments must not be null");
        }
        if (clientFactory == null) {
            throw new IllegalArgumentException("SSH client factory must not be null");
        }
        return open(
                endpoint,
                options,
                clientFactory,
                List.of(UserAuthPublicKeyFactory.INSTANCE),
                new AuthenticationSetup() {
                    @Override
                    public void configure(ClientSession session) {
                        session.addPublicKeyIdentity(credentials.keyPair());
                    }
                });
    }

    static MinaSshOperation openWithPassword(
            SshEndpoint endpoint,
            BootstrapPassword password,
            ProvisioningOptions options) throws ProvisioningException {
        return openWithPassword(endpoint, password, options, SshClient::setUpDefaultClient);
    }

    @TestOnly
    static MinaSshOperation openWithPassword(
            SshEndpoint endpoint,
            BootstrapPassword password,
            ProvisioningOptions options,
            Supplier<SshClient> clientFactory) throws ProvisioningException {
        if (password == null) {
            throw new IllegalArgumentException("Bootstrap password must not be null");
        }
        try {
            return password.useOnce(value -> open(
                    endpoint,
                    options,
                    clientFactory,
                    List.of(UserAuthPasswordFactory.INSTANCE),
                    new AuthenticationSetup() {
                        @Override
                        public void configure(SshClient client) {
                            client.setPasswordIdentityProvider(
                                    PasswordIdentityProvider.wrapPasswords(value));
                        }

                        @Override
                        public void clear(SshClient client, ClientSession session) {
                            if (session != null) {
                                session.setPasswordIdentityProvider(
                                        PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
                            }
                            client.setPasswordIdentityProvider(
                                    PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
                        }
                    }));
        } catch (ProvisioningException error) {
            throw error;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new ProvisioningException(
                    ProvisioningFailure.AUTHENTICATION,
                    "SSH password authentication failed",
                    error);
        }
    }

    private static MinaSshOperation open(
            SshEndpoint endpoint,
            ProvisioningOptions options,
            Supplier<SshClient> clientFactory,
            List<UserAuthFactory> userAuthFactories,
            AuthenticationSetup authentication) throws ProvisioningException {
        if (endpoint == null || options == null) {
            throw new IllegalArgumentException("SSH operation arguments must not be null");
        }
        if (clientFactory == null || authentication == null) {
            throw new IllegalArgumentException("SSH operation configuration must not be null");
        }
        SshClient client = clientFactory.get();
        AtomicBoolean hostRejected = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        ClientSession session = null;
        ScheduledFuture<?> watchdog = null;
        try {
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
            client.setUserAuthFactories(userAuthFactories);
            authentication.configure(client);
            client.setServerKeyVerifier((activeSession, address, key) -> verifyHostKey(
                    endpoint.expectedHostKey(), key, hostRejected));
            client.start();
            watchdog = WATCHDOG.schedule(() -> {
                timedOut.set(true);
                client.close(true);
            }, options.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            ConnectFuture connect = client.connect(endpoint.username(), endpoint.host(), endpoint.port());
            session = connect.verify(options.connectTimeout()).getSession();
            authentication.configure(session);
            session.auth().verify(options.authenticationTimeout());
            authentication.clear(client, session);
            return new MinaSshOperation(client, session, options, timedOut, watchdog);
        } catch (IOException | RuntimeException error) {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            clearAuthentication(authentication, client, session, error);
            closeAfterFailure(session, client, error);
            if (timedOut.get() || causedByTimeout(error)) {
                throw new ProvisioningException(
                        ProvisioningFailure.TIMEOUT, "SSH provisioning operation timed out", error);
            }
            if (hostRejected.get()) {
                throw new ProvisioningException(
                        ProvisioningFailure.HOST_IDENTITY, "SSH server host key was rejected", error);
            }
            ProvisioningFailure failure = session == null
                    ? ProvisioningFailure.CONNECTION
                    : ProvisioningFailure.AUTHENTICATION;
            throw new ProvisioningException(failure, message(failure), error);
        }
    }

    private static void clearAuthentication(
            AuthenticationSetup authentication,
            SshClient client,
            ClientSession session,
            Throwable failure) {
        try {
            authentication.clear(client, session);
        } catch (RuntimeException clearError) {
            failure.addSuppressed(clearError);
        }
    }

    public synchronized RemoteCommandResult execute(String command, byte[] input) throws ProvisioningException {
        try (OwnedCommandInput suppliedInput = new OwnedCommandInput(input)) {
            return execute(command, suppliedInput.stream());
        }
    }

    public synchronized RemoteCommandResult execute(
            String command,
            InputStream input) throws ProvisioningException {
        requireOpen();
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Remote command is invalid");
        }
        if (input == null) {
            throw new IllegalArgumentException("Remote command input must not be null");
        }
        BoundedOutput stdout = new BoundedOutput(OUTPUT_LIMIT);
        BoundedOutput stderr = new BoundedOutput(OUTPUT_LIMIT);
        try (ClientChannel channel = session.createExecChannel(command)) {
            channel.setIn(input);
            channel.setOut(stdout);
            channel.setErr(stderr);
            channel.open().verify(options.commandTimeout());
            Set<ClientChannelEvent> events = channel.waitFor(
                    EnumSet.of(ClientChannelEvent.CLOSED), options.commandTimeout());
            if (events.contains(ClientChannelEvent.TIMEOUT)) {
                channel.close(true);
                throw timeout();
            }
            if (timedOut.get()) {
                throw timeout();
            }
            Integer exitStatus = channel.getExitStatus();
            if (exitStatus == null) {
                throw new ProvisioningException(
                        ProvisioningFailure.REMOTE_COMMAND, "Remote SSH command returned no exit status");
            }
            return new RemoteCommandResult(
                    exitStatus, stdout.bytes(), stderr.bytes(), stdout.truncated(), stderr.truncated());
        } catch (ProvisioningException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            if (timedOut.get() || causedByTimeout(error)) {
                throw timeout(error);
            }
            throw new ProvisioningException(
                    ProvisioningFailure.REMOTE_COMMAND, "Remote SSH command failed", error);
        }
    }

    private ProvisioningException timeout() {
        return timeout(null);
    }

    private ProvisioningException timeout(Throwable cause) {
        return new ProvisioningException(
                ProvisioningFailure.TIMEOUT, "SSH provisioning operation timed out", cause);
    }

    private static boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requireOpen() throws ProvisioningException {
        if (closed) {
            throw new ProvisioningException(
                    ProvisioningFailure.CONNECTION, "SSH provisioning operation is closed");
        }
        if (timedOut.get()) {
            throw timeout();
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            watchdog.cancel(false);
            try {
                session.close();
            } catch (IOException ignored) {
                // The session may already have been closed by the watchdog.
            } finally {
                client.stop();
            }
        }
    }

    private static boolean verifyHostKey(PublicKey expected, PublicKey actual, AtomicBoolean rejected) {
        boolean accepted = org.apache.sshd.common.config.keys.KeyUtils.compareKeys(expected, actual);
        if (!accepted) {
            rejected.set(true);
        }
        return accepted;
    }

    private static String message(ProvisioningFailure failure) {
        return failure == ProvisioningFailure.AUTHENTICATION
                ? "SSH public-key authentication failed"
                : "SSH connection failed";
    }

    private static void closeAfterFailure(ClientSession session, SshClient client, Throwable failure) {
        if (session != null) {
            try {
                session.close();
            } catch (IOException closeError) {
                failure.addSuppressed(closeError);
            }
        }
        try {
            client.stop();
        } catch (RuntimeException closeError) {
            failure.addSuppressed(closeError);
        }
        if (!client.isClosed()) {
            try {
                client.close(true);
            } catch (RuntimeException closeError) {
                failure.addSuppressed(closeError);
            }
        }
    }

    private static final class BoundedOutput extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream output;
        private boolean truncated;

        private BoundedOutput(int limit) {
            this.limit = limit;
            output = new ByteArrayOutputStream(limit);
        }

        @Override
        public synchronized void write(int value) {
            if (output.size() < limit) {
                output.write(value);
            } else {
                truncated = true;
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            int accepted = Math.min(length, limit - output.size());
            if (accepted > 0) {
                output.write(bytes, offset, accepted);
            }
            if (accepted < length) {
                truncated = true;
            }
        }

        private synchronized byte[] bytes() {
            return output.toByteArray();
        }

        private synchronized boolean truncated() {
            return truncated;
        }
    }

    private static final class WatchdogThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-provisioning-watchdog");
            thread.setDaemon(true);
            return thread;
        }
    }

    private interface AuthenticationSetup {
        default void configure(SshClient client) {
        }

        default void configure(ClientSession session) {
        }

        default void clear(SshClient client, ClientSession session) {
        }
    }
}
