package pro.deta.orion.transport.git.auth;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKey;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.keyprovider.MappedKeyPairProvider;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.UserAuthFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionSshAuthenticatorTest {
    private static final long AUTH_TIMEOUT_SECONDS = 5;
    private static final List<org.apache.sshd.client.auth.UserAuthFactory> PUBLIC_KEY_ONLY =
            List.of(UserAuthPublicKeyFactory.INSTANCE);
    private static final List<org.apache.sshd.client.auth.UserAuthFactory> PUBLIC_KEY_AND_INTERACTIVE =
            List.of(UserAuthPublicKeyFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE);
    private static final List<org.apache.sshd.client.auth.UserAuthFactory> INTERACTIVE_ONLY =
            List.of(UserAuthKeyboardInteractiveFactory.INSTANCE);

    @TempDir
    Path tempDir;

    @Test
    void authenticatesNamedAndGitUsersWithRegisteredKeys() throws Exception {
        KeyPair aliceKey = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice", aliceKey.getPublic());

            authenticateSuccessfully(fixture, "alice", List.of(aliceKey), PUBLIC_KEY_ONLY, null);
            authenticateSuccessfully(fixture, "git", List.of(aliceKey), PUBLIC_KEY_ONLY, null);

            assertThat(fixture.accessControl.authenticatedUserIds).contains("alice");
        }
    }

    @Test
    void rejectsUnknownUsersAndUnknownGitKeys() throws Exception {
        KeyPair key = keyPair();
        try (Fixture fixture = new Fixture()) {
            RecordingInteraction interaction = new RecordingInteraction("correct-password", "all");

            assertAuthenticationFails(
                    fixture,
                    "missing",
                    List.of(key),
                    PUBLIC_KEY_AND_INTERACTIVE,
                    interaction);
            assertAuthenticationFails(fixture, "git", List.of(key), PUBLIC_KEY_ONLY, null);

            assertThat(interaction.callCount).isZero();
            assertThat(fixture.accessControl.keysFor("missing")).isEmpty();
        }
    }

    @Test
    void invalidPasswordNeverDisclosesProvedCandidates() throws Exception {
        KeyPair candidate = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice");
            RecordingInteraction interaction = new RecordingInteraction("1", "all");

            assertAuthenticationFails(
                    fixture,
                    "alice",
                    List.of(candidate),
                    PUBLIC_KEY_AND_INTERACTIVE,
                    interaction);

            assertThat(interaction.prompts).containsExactly(List.of("Orion password: "));
            assertThat(interaction.echoes).containsExactly(List.of(false));
            assertThat(interaction.instructions.getFirst())
                    .doesNotContain(KeyUtils.getKeyType(candidate.getPublic()))
                    .doesNotContain(KeyUtils.getFingerPrint(candidate.getPublic()))
                    .doesNotContain("candidate", "Keys (`all`");
            assertThat(fixture.accessControl.keysFor("alice")).isEmpty();
        }
    }

    @Test
    void enrollsProvedCandidatesInASecondRoundAndAuthenticatesTheSameSession() throws Exception {
        KeyPair first = keyPair();
        KeyPair second = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice");
            RecordingInteraction interaction = new RecordingInteraction("correct-password", "2,1");

            authenticateSuccessfully(
                    fixture,
                    "alice",
                    List.of(first, first, second),
                    PUBLIC_KEY_AND_INTERACTIVE,
                    interaction);

            String firstFingerprint = KeyUtils.getFingerPrint(first.getPublic());
            String secondFingerprint = KeyUtils.getFingerPrint(second.getPublic());
            assertThat(interaction.instructions).hasSize(2);
            assertThat(interaction.prompts)
                    .containsExactly(List.of("Orion password: "), List.of("Keys (`all`, numbers, or OpenSSH key): "));
            assertThat(interaction.instructions.get(1))
                    .containsOnlyOnce(firstFingerprint)
                    .containsOnlyOnce(secondFingerprint);
            assertThat(fixture.accessControl.keysFor("alice")).hasSize(2);
        }
    }

    @Test
    void recoveredRootAuthenticationDefersAclMutationToTheDedicatedCommand() throws Exception {
        KeyPair candidate = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addRecoveryRoot();

            authenticateSuccessfully(
                    fixture,
                    "root",
                    List.of(candidate),
                    PUBLIC_KEY_AND_INTERACTIVE,
                    new RecordingInteraction("correct-password", "all"));

            assertThat(fixture.accessControl.keysFor("root")).isEmpty();
        }
    }

    @Test
    void validPasswordWithoutCandidatesAuthenticatesInOneRound() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice");
            RecordingInteraction interaction = new RecordingInteraction("correct-password", null);

            authenticateSuccessfully(fixture, "alice", List.of(), INTERACTIVE_ONLY, interaction);

            assertThat(interaction.prompts).containsExactly(List.of("Orion password: "));
            assertThat(fixture.accessControl.authenticatedUserIds).contains("alice");
        }
    }

    @Test
    void enrollsAManuallyPastedOpenSshKeyAfterPasswordAuthentication() throws Exception {
        KeyPair candidate = keyPair();
        KeyPair pasted = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice");
            RecordingInteraction interaction = new RecordingInteraction(
                    "correct-password",
                    PublicKeyEntry.toString(pasted.getPublic()) + " alice@laptop");

            authenticateSuccessfully(fixture, "alice", List.of(candidate), PUBLIC_KEY_AND_INTERACTIVE, interaction);

            assertThat(interaction.instructions).hasSize(2);
            assertThat(fixture.accessControl.keysFor("alice"))
                    .singleElement()
                    .satisfies(key -> assertThat(key.getEncoded()).isEqualTo(pasted.getPublic().getEncoded()));
        }
    }

    @Test
    void malformedSelectionCannotEnrollKeys() throws Exception {
        KeyPair candidate = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice");

            assertAuthenticationFails(
                    fixture,
                    "alice",
                    List.of(candidate),
                    PUBLIC_KEY_AND_INTERACTIVE,
                    new RecordingInteraction("correct-password", "1,1"));
            assertThat(fixture.accessControl.keysFor("alice")).isEmpty();
        }
    }

    @Test
    void provedCandidatesAreIsolatedBetweenConnections() throws Exception {
        KeyPair candidate = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice");
            RecordingInteraction firstInteraction = new RecordingInteraction("wrong-password", "all");

            assertAuthenticationFails(
                    fixture,
                    "alice",
                    List.of(candidate),
                    PUBLIC_KEY_AND_INTERACTIVE,
                    firstInteraction);
            assertThat(firstInteraction.instructions.getFirst())
                    .doesNotContain(KeyUtils.getFingerPrint(candidate.getPublic()));

            RecordingInteraction secondInteraction = new RecordingInteraction("correct-password", null);
            authenticateSuccessfully(
                    fixture,
                    "alice",
                    List.of(),
                    INTERACTIVE_ONLY,
                    secondInteraction);

            assertThat(secondInteraction.instructions).hasSize(1);
            assertThat(fixture.accessControl.keysFor("alice")).isEmpty();
        }
    }

    @Test
    void aPublicKeyProbeWithoutASignatureIsNotAnEnrollmentCandidate() throws Exception {
        KeyPair probed = keyPair();
        try (Fixture fixture = new Fixture()) {
            fixture.accessControl.addUser("alice");
            RecordingInteraction probeInteraction = new RecordingInteraction("correct-password", null);

            authenticateSuccessfully(
                    fixture,
                    "alice",
                    List.of(probed),
                    List.of(PROBE_ONLY_FACTORY, UserAuthKeyboardInteractiveFactory.INSTANCE),
                    probeInteraction);

            assertThat(probeInteraction.instructions).hasSize(1);
            assertThat(fixture.accessControl.keysFor("alice")).isEmpty();
        }
    }

    private static final UserAuthPublicKeyFactory PROBE_ONLY_FACTORY = new UserAuthPublicKeyFactory() {
        @Override
        public UserAuthPublicKey createUserAuth(ClientSession session) {
            return new UserAuthPublicKey(getSignatureFactories()) {
                @Override
                protected boolean processAuthDataRequest(
                        ClientSession clientSession,
                        String service,
                        Buffer buffer) {
                    current = null;
                    keys = Collections.emptyIterator();
                    currentAlgorithms.clear();
                    return false;
                }
            };
        }
    };

    private static void authenticateSuccessfully(
            Fixture fixture,
            String username,
            List<KeyPair> keys,
            List<org.apache.sshd.client.auth.UserAuthFactory> factories,
            UserInteraction interaction) throws Exception {
        try (SshClient client = client(factories, interaction)) {
            try (ClientSession session = connect(client, fixture, username, keys)) {
                session.auth().verify(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(session.isAuthenticated()).isTrue();
            }
        }
    }

    private static void assertAuthenticationFails(
            Fixture fixture,
            String username,
            List<KeyPair> keys,
            List<org.apache.sshd.client.auth.UserAuthFactory> factories,
            UserInteraction interaction) throws Exception {
        try (SshClient client = client(factories, interaction)) {
            try (ClientSession session = connect(client, fixture, username, keys)) {
                assertThatThrownBy(() -> session.auth().verify(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .isInstanceOf(IOException.class);
                assertThat(session.isAuthenticated()).isFalse();
            }
        }
    }

    private static SshClient client(
            List<org.apache.sshd.client.auth.UserAuthFactory> factories,
            UserInteraction interaction) {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.setUserAuthFactories(factories);
        client.setUserInteraction(interaction);
        client.setAgentFactory(null);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.start();
        return client;
    }

    private static ClientSession connect(
            SshClient client,
            Fixture fixture,
            String username,
            List<KeyPair> keys) throws Exception {
        AttributeRepository context = AttributeRepository.ofKeyValuePair(
                UserAuthPublicKey.USE_DEFAULT_IDENTITIES,
                false);
        HostConfigEntry host = new HostConfigEntry();
        host.setHost("127.0.0.1");
        host.setHostName("127.0.0.1");
        host.setPort(fixture.port());
        host.setUsername(username);
        host.setIdentitiesOnly(true);
        ClientSession session = client.connect(host, context, null)
                .verify(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .getSession();
        for (KeyPair key : keys) {
            session.addPublicKeyIdentity(key);
        }
        return session;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private final class Fixture implements AutoCloseable {
        private final RecordingAccessControlService accessControl = new RecordingAccessControlService();
        private final SshServer server = SshServer.setUpDefaultServer();

        private Fixture() throws Exception {
            OrionSshAuthenticator authenticator = new OrionSshAuthenticator(accessControl);
            KeyPair hostKey = keyPair();
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(new MappedKeyPairProvider(Map.of(
                    org.apache.sshd.common.config.keys.KeyUtils.getKeyType(hostKey.getPublic()),
                    hostKey)));
            server.setPublickeyAuthenticator(authenticator);
            List<UserAuthFactory> factories = List.of(
                    new EnrollmentAwarePublicKeyAuthFactory(authenticator),
                    new PasswordKeyboardInteractiveAuthFactory(authenticator));
            server.setUserAuthFactories(factories);
            server.start();
        }

        private int port() {
            return server.getBoundAddresses().stream()
                    .map(address -> (java.net.InetSocketAddress) address)
                    .findFirst()
                    .orElseThrow()
                    .getPort();
        }

        @Override
        public void close() throws IOException {
            server.close(true);
        }
    }

    private static final class RecordingInteraction implements UserInteraction {
        private final String password;
        private final String selection;
        private int callCount;
        private final List<String> instructions = new ArrayList<>();
        private final List<List<String>> prompts = new ArrayList<>();
        private final List<List<Boolean>> echoes = new ArrayList<>();

        private RecordingInteraction(String password, String selection) {
            this.password = password;
            this.selection = selection;
        }

        @Override
        public String[] interactive(
                ClientSession session,
                String name,
                String instruction,
                String lang,
                String[] prompt,
                boolean[] echo) {
            callCount++;
            instructions.add(instruction);
            prompts.add(List.of(prompt));
            List<Boolean> roundEchoes = new ArrayList<>(echo.length);
            for (boolean value : echo) {
                roundEchoes.add(value);
            }
            echoes.add(List.copyOf(roundEchoes));
            return callCount == 1 ? new String[]{password} : new String[]{selection};
        }

        @Override
        public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
            return null;
        }
    }

    private static final class RecordingAccessControlService implements OrionAccessControlService {
        private final Map<String, LinkedHashMap<String, PublicKey>> keysByUser = new LinkedHashMap<>();
        private final List<String> authenticatedUserIds = new ArrayList<>();
        private boolean recoveryRoot;

        private void addRecoveryRoot() {
            addUser("root");
            recoveryRoot = true;
        }

        private void addUser(String username, PublicKey... keys) {
            LinkedHashMap<String, PublicKey> userKeys = keysByUser.computeIfAbsent(
                    username,
                    ignored -> new LinkedHashMap<>());
            for (PublicKey key : keys) {
                userKeys.put(fingerprint(key), key);
            }
        }

        private List<PublicKey> keysFor(String username) {
            return List.copyOf(keysByUser.getOrDefault(username, new LinkedHashMap<>()).values());
        }

        @Override
        public void addKeyToUser(String username, String publicKey) {
            addSshKeysToUser(username, List.of(publicKey));
        }

        @Override
        public void addSshKeysToUser(String username, List<String> publicKeys) {
            LinkedHashMap<String, PublicKey> userKeys = keysByUser.get(username);
            if (userKeys == null) {
                throw new IllegalStateException("unknown user");
            }
            for (String publicKey : publicKeys) {
                PublicKey parsed = pro.deta.orion.util.KeyUtils.readPublicKeyFromString(publicKey);
                userKeys.putIfAbsent(fingerprint(parsed), parsed);
            }
        }

        @Override
        public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean userExists(String userName) {
            return keysByUser.containsKey(userName);
        }

        @Override
        public AuthenticationResult authenticateUser(String userName, byte[] credential) {
            if (keysByUser.containsKey(userName)
                    && Arrays.equals("correct-password".getBytes(StandardCharsets.UTF_8), credential)) {
                return success(userName);
            }
            return AuthenticationResult.failure("authentication failed");
        }

        @Override
        public SshKeyEnrollmentAuthentication authenticateSshKeyEnrollment(
                String userName,
                byte[] credential) {
            AuthenticationResult result = authenticateUser(userName, credential);
            return switch (result) {
                case AuthenticationResult.Success(var identity) -> SshKeyEnrollmentAuthentication.success(
                        identity,
                        recoveryRoot && "root".equalsIgnoreCase(userName) ? "generation-1" : null);
                case AuthenticationResult.Failure(var reason, var throwable) ->
                        SshKeyEnrollmentAuthentication.failure(reason, throwable);
            };
        }

        @Override
        public AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey) {
            PublicKey matched = matchingKey(keysByUser.get(userName), encodedPublicKey);
            return matched == null ? AuthenticationResult.failure("authentication failed") : success(userName);
        }

        @Override
        public AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey) {
            List<String> matchingUsers = new ArrayList<>();
            for (Map.Entry<String, LinkedHashMap<String, PublicKey>> entry : keysByUser.entrySet()) {
                if (matchingKey(entry.getValue(), encodedPublicKey) != null) {
                    matchingUsers.add(entry.getKey());
                }
            }
            return matchingUsers.size() == 1
                    ? success(matchingUsers.getFirst())
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

        private AuthenticationResult success(String username) {
            authenticatedUserIds.add(username);
            return AuthenticationResult.success(new InternalUserImpl(username, List.of()));
        }

        private static PublicKey matchingKey(Map<String, PublicKey> keys, byte[] encodedPublicKey) {
            if (keys == null) {
                return null;
            }
            for (PublicKey key : keys.values()) {
                if (Arrays.equals(key.getEncoded(), encodedPublicKey)) {
                    return key;
                }
            }
            return null;
        }

        private static String fingerprint(PublicKey key) {
            return KeyUtils.getFingerPrint(key);
        }
    }
}
