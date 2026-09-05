package pro.deta.orion.transport.git;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.render.PlainCommandRenderer;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.crypto.SshHostKeyService;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.transport.git.auth.OrionSshAuthenticator;
import pro.deta.orion.transport.git.command.SshCredentialCommandCatalog;
import pro.deta.orion.transport.git.ssh.SshCommandFactory;
import pro.deta.orion.util.ConfigurationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;

class GitSshTransportStateMachineTest {
    @TempDir
    private Path tempDir;

    private GitSshTransportService service;
    private OrionExecutor executor;

    @AfterEach
    void stopService() {
        if (service != null) {
            service.onStop();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void sshGitStateMachineUsesGenericServiceLifecycleAdapter() {
        assertEquals(ServiceLifecycleStateMachineAdapter.class, GitSshTransportStateMachine.class.getSuperclass());
    }

    @Test
    void bindFailureMovesStateMachineToError() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            service = service(occupied.getLocalPort());
            GitSshTransportStateMachine machine = new GitSshTransportStateMachine(() -> service);

            assertThrows(StateTransitionFailedException.class, machine::start);

            assertEquals(ERR, machine.currentState());
            assertFalse(service.isRunning());
        }
    }

    @Test
    void reportsTheDynamicallyBoundPortOnlyWhileRunning() {
        service = service(0);
        service.onStart();

        assertTrue(service.boundPort() > 0);

        service.onStop();
        assertEquals(0, service.boundPort());
        service = null;
    }

    @Test
    void disablesPasswordAuthenticationAndAcceptsNamedAndGitPublicKeys() throws Exception {
        KeyPair keyPair = keyPair();
        RecordingAccessControlService accessControl = new RecordingAccessControlService(keyPair);
        service = service(0, accessControl);
        service.onStart();

        assertPasswordAuthenticationFails(service.boundPort(), "alice", "accepted-by-legacy-authenticator");
        assertPublicKeyAuthenticationSucceeds(service.boundPort(), "alice", keyPair);
        assertPublicKeyAuthenticationSucceeds(service.boundPort(), "git", keyPair);
    }

    @Test
    void namedUserShellCannotStartAnOperatingSystemProcess() throws Exception {
        KeyPair keyPair = keyPair();
        Path marker = tempDir.resolve("os-shell-marker");
        service = service(0, new RecordingAccessControlService(keyPair));
        service.onStart();

        try (SshClient client = client(List.of(UserAuthPublicKeyFactory.INSTANCE));
             ClientSession session = connect(client, service.boundPort(), "alice")) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(5, TimeUnit.SECONDS);
            try (ClientChannel channel = session.createShellChannel()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PipedInputStream shellInput = new PipedInputStream();
                try (PipedOutputStream clientInput = new PipedOutputStream(shellInput)) {
                    channel.setIn(shellInput);
                    channel.setOut(output);
                    channel.setErr(new ByteArrayOutputStream());
                    channel.open().verify(5, TimeUnit.SECONDS);
                    awaitOccurrences(output, "@orion", 1);

                    clientInput.write("help\n".getBytes(StandardCharsets.UTF_8));
                    clientInput.flush();
                    awaitOccurrences(output, "@orion", 2);
                    clientInput.write(("touch " + marker + "\n").getBytes(StandardCharsets.UTF_8));
                    clientInput.flush();
                    awaitContains(output, "UNKNOWN_COMMAND");
                    clientInput.write("quit\n".getBytes(StandardCharsets.UTF_8));
                    clientInput.flush();
                }
                assertTrue(channel.waitFor(
                                EnumSet.of(ClientChannelEvent.CLOSED),
                                TimeUnit.SECONDS.toMillis(5))
                        .contains(ClientChannelEvent.CLOSED));

                assertFalse(Files.exists(marker));
                assertEquals(0, channel.getExitStatus());
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("UNKNOWN_COMMAND"));
            }
        }
    }

    @Test
    void realExecAndInteractiveShellUseCurrentCredentialCommands() throws Exception {
        KeyPair keyPair = keyPair();
        service = service(0, new RecordingAccessControlService(keyPair));
        service.onStart();

        try (SshClient client = client(List.of(UserAuthPublicKeyFactory.INSTANCE));
             ClientSession session = connect(client, service.boundPort(), "alice")) {
            session.addPublicKeyIdentity(keyPair);
            session.auth().verify(5, TimeUnit.SECONDS);
            try (ClientChannel exec = session.createExecChannel("/auth/key ls")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                exec.setOut(output);
                exec.setErr(new ByteArrayOutputStream());
                exec.open().verify(5, TimeUnit.SECONDS);
                exec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(5));
                assertEquals(0, exec.getExitStatus());
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("true"));
            }

            try (ClientChannel shell = session.createShellChannel()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PipedInputStream shellInput = new PipedInputStream();
                try (PipedOutputStream clientInput = new PipedOutputStream(shellInput)) {
                    shell.setIn(shellInput);
                    shell.setOut(output);
                    shell.setErr(new ByteArrayOutputStream());
                    shell.open().verify(5, TimeUnit.SECONDS);
                    awaitOccurrences(output, "@orion", 1);
                    clientInput.write("/auth/key ls\r".getBytes(StandardCharsets.UTF_8));
                    clientInput.flush();
                    awaitContains(output, "true");
                    clientInput.write("quit\r".getBytes(StandardCharsets.UTF_8));
                    clientInput.flush();
                }
            }
        }
    }

    private static void awaitContains(ByteArrayOutputStream output, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!output.toString(StandardCharsets.UTF_8).contains(expected)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for terminal output: " + expected);
            }
            Thread.sleep(10);
        }
    }

    private static void awaitOccurrences(
            ByteArrayOutputStream output,
            String expected,
            int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (occurrences(output.toString(StandardCharsets.UTF_8), expected) < count) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for terminal prompt " + count);
            }
            Thread.sleep(10);
        }
    }

    private static int occurrences(String value, String expected) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            result++;
            offset += expected.length();
        }
        return result;
    }

    private GitSshTransportService service(int port) {
        return service(port, new RecordingAccessControlService(null));
    }

    private GitSshTransportService service(int port, OrionAccessControlService accessControlService) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getTransport().getSsh().setEnabled(true);
        configuration.getTransport().getSsh().setAddress("127.0.0.1");
        configuration.getTransport().getSsh().setPort(port);
        ConfigurationContext configurationContext = new ConfigurationContext(configuration);
        SshHostKeyService hostKeyService = new SshHostKeyService(configurationContext);
        CommandDispatcher dispatcher = new DefaultCommandDispatcher(
                new CommandLineParser(),
                new SshCredentialCommandCatalog(accessControlService).commandTree(),
                new pro.deta.orion.command.CommandRowQuery());
        executor = new OrionExecutor(2, new OrionThreadFactory());
        SshCommandFactory commandFactory = new SshCommandFactory(
                executor,
                dispatcher,
                new PlainCommandRenderer(),
                null,
                null,
                accessControlService);
        OrionShell shell = new OrionShell(
                dispatcher,
                new CommandNavigator(CommandNode.builder().build()),
                executor);
        OrionSshAuthenticator authenticator = new OrionSshAuthenticator(accessControlService);
        return new GitSshTransportService(
                configuration,
                commandFactory,
                shell,
                () -> hostKeyService,
                authenticator);
    }

    private static void assertPasswordAuthenticationFails(int port, String username, String password)
            throws Exception {
        try (SshClient client = client(List.of(UserAuthPasswordFactory.INSTANCE))) {
            try (ClientSession session = connect(client, port, username)) {
                session.addPasswordIdentity(password);
                assertThrows(IOException.class, () -> session.auth().verify(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertPublicKeyAuthenticationSucceeds(int port, String username, KeyPair keyPair)
            throws Exception {
        try (SshClient client = client(List.of(UserAuthPublicKeyFactory.INSTANCE))) {
            try (ClientSession session = connect(client, port, username)) {
                session.addPublicKeyIdentity(keyPair);
                session.auth().verify(5, TimeUnit.SECONDS);
                assertTrue(session.isAuthenticated());
            }
        }
    }

    private static SshClient client(List<org.apache.sshd.client.auth.UserAuthFactory> factories) {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.setUserAuthFactories(factories);
        client.setAgentFactory(null);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.start();
        return client;
    }

    private static ClientSession connect(SshClient client, int port, String username) throws Exception {
        HostConfigEntry host = new HostConfigEntry();
        host.setHost("127.0.0.1");
        host.setHostName("127.0.0.1");
        host.setPort(port);
        host.setUsername(username);
        host.setIdentitiesOnly(true);
        return client.connect(host, null, null)
                .verify(5, TimeUnit.SECONDS)
                .getSession();
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static final class RecordingAccessControlService implements OrionAccessControlService {
        private final KeyPair acceptedKey;

        private RecordingAccessControlService(KeyPair acceptedKey) {
            this.acceptedKey = acceptedKey;
        }

        @Override
        public SshCredentialListResult listSshCredentials(String userId) {
            if (!"alice".equals(userId) || acceptedKey == null) {
                return SshCredentialListResult.success(List.of());
            }
            return SshCredentialListResult.success(List.of(new SshCredential(
                    org.apache.sshd.common.config.keys.KeyUtils.getKeyType(acceptedKey.getPublic()),
                    org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(acceptedKey.getPublic()))));
        }

        @Override
        public void addKeyToUser(String username, String publicKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addSshKeysToUser(String username, List<String> publicKeys) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean userExists(String userName) {
            return "alice".equals(userName);
        }

        @Override
        public AuthenticationResult authenticateUser(String userName, byte[] credential) {
            return success();
        }

        @Override
        public AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey) {
            return "alice".equals(userName) && keyMatches(encodedPublicKey)
                    ? success()
                    : AuthenticationResult.failure("authentication failed");
        }

        @Override
        public AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey) {
            return keyMatches(encodedPublicKey)
                    ? success()
                    : AuthenticationResult.failure("authentication failed");
        }

        @Override
        public AuthenticationResult authenticateToken(byte[] token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenIssueResult authenticateUserAndIssueToken(
                String userName,
                byte[] credential,
                long expiresInSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenIssueResult issueTokenFor(UserIdentity userIdentity, long expiresInSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] accessControlConfigurationFile() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveAccessControlConfigurationFile(byte[] content) {
            throw new UnsupportedOperationException();
        }

        private boolean keyMatches(byte[] encodedPublicKey) {
            return acceptedKey != null
                    && Arrays.equals(acceptedKey.getPublic().getEncoded(), encodedPublicKey);
        }

        private AuthenticationResult success() {
            return AuthenticationResult.success(new InternalUserImpl("alice", List.of()));
        }
    }
}
