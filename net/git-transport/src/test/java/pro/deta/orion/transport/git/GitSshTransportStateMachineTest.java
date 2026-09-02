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
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.crypto.SshHostKeyService;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.transport.git.auth.OrionSshAuthenticator;
import pro.deta.orion.transport.git.auth.SshEnrollmentTokenStore;
import pro.deta.orion.transport.git.ssh.SshCommandFactory;
import pro.deta.orion.util.ConfigurationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    @AfterEach
    void stopService() {
        if (service != null) {
            service.onStop();
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
    void startsTheEnrollmentTokenStoreWhenSshStarts() {
        service = service(0);

        service.onStart();

        assertTrue(Files.exists(tempDir.resolve("ssh-enrollment-token.properties")));
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
                ByteArrayOutputStream error = new ByteArrayOutputStream();
                channel.setIn(new ByteArrayInputStream(
                        ("touch " + marker + "\nexit\n").getBytes(StandardCharsets.UTF_8)));
                channel.setOut(new ByteArrayOutputStream());
                channel.setErr(error);
                channel.open().verify(5, TimeUnit.SECONDS);
                assertTrue(channel.waitFor(
                                EnumSet.of(ClientChannelEvent.CLOSED),
                                TimeUnit.SECONDS.toMillis(5))
                        .contains(ClientChannelEvent.CLOSED));

                assertFalse(Files.exists(marker));
                assertEquals(127, channel.getExitStatus());
                assertTrue(error.toString(StandardCharsets.UTF_8).contains("You may clone a repository"));
            }
        }
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
        SshCommandFactory commandFactory = new SshCommandFactory(null, null, null, null);
        SshEnrollmentTokenStore tokenStore = new SshEnrollmentTokenStore(
                configurationContext,
                OrionRuntimeOptions.defaults());
        OrionSshAuthenticator authenticator = new OrionSshAuthenticator(accessControlService, tokenStore);
        return new GitSshTransportService(
                configuration,
                commandFactory,
                () -> hostKeyService,
                authenticator,
                tokenStore);
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
