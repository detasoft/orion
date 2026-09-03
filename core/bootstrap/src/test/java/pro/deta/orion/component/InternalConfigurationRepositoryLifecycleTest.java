package pro.deta.orion.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.util.KeyUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

class InternalConfigurationRepositoryLifecycleTest {
    private static final String REPOSITORY_NAME = "internal/configuration";
    private static final String CONFIGURATION_REF = "refs/heads/configuration";
    private static final String ACL_PATH = "config/orion.xml";

    @TempDir
    private Path tempDir;

    @Test
    void bootstrapsOnceAndReusesTheCommittedAclOnRestart() throws Exception {
        OrionConfiguration configuration = configuration();
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        char[] rootPassword;
        String firstVersion;
        try {
            System.setOut(new PrintStream(processOutput, true, StandardCharsets.UTF_8));
            OrionComponent first = component(configuration);
            OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
            try {
                assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
                rootPassword = first.orionAccessControlService()
                        .plainRootToken(PlainRootTokenAccessForTests.create());
                assertAuthenticated(first, "root", new String(rootPassword));
                assertThat(first.nativeGitRepositoryProvider().repositoryNames())
                        .containsExactly(REPOSITORY_NAME);
                GitRepositoryFileSnapshot snapshot = repository(first).loadFiles(
                        CONFIGURATION_REF,
                        List.of(ACL_PATH));
                firstVersion = snapshot.version().orElseThrow();
                AccessControl acl = new XmlService().deserialize(
                        new ByteArrayInputStream(snapshot.files().get(ACL_PATH)));
                assertThat(acl.getUsers()).extracting(AccessControl.User::getId).contains("root");
            } finally {
                assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
            }

            OrionComponent restarted = component(configuration);
            OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
            try {
                assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
                assertAuthenticated(restarted, "root", new String(rootPassword));
                assertThat(repository(restarted).loadFiles(CONFIGURATION_REF, List.of(ACL_PATH)).version())
                        .contains(firstVersion);
                assertThatThrownBy(() -> restarted.orionAccessControlService()
                        .plainRootToken(PlainRootTokenAccessForTests.create()))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
            }
        } finally {
            System.setOut(originalOut);
        }

        assertThat(processOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");
    }

    @Test
    void rotationRevokesRetainedServerIdentityFromRootSsh() throws Exception {
        OrionConfiguration configuration = configuration();
        KeyPair oldIdentity = keyPair();
        KeyPair activeIdentity = keyPair();

        OrionComponent first = component(configuration, new TestServerIdentity(oldIdentity, List.of()));
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            assertSshAuthenticated(first, "root", oldIdentity);
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        OrionComponent rotated = component(
                configuration,
                new TestServerIdentity(activeIdentity, List.of(oldIdentity)));
        OrionApplicationLifecycle rotatedLifecycle = rotated.orionApplicationLifecycle();
        try {
            assertThat(rotatedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertSshAuthenticated(rotated, "root", activeIdentity);
            assertSshAuthenticationFailed(rotated, "root", oldIdentity);
        } finally {
            assertThat(rotatedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void reloadsReceivePackPublicationsAndRetainsLastValidAcl() throws Exception {
        OrionComponent component = component(configuration());
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            NativeGitRepository repository = repository(component);

            publishCandidate(repository, "push", aclBytes("push-user", "push-password"));
            assertAuthenticated(component, "push-user", "push-password");

            publishCandidate(repository, "invalid", "<not-valid-xml".getBytes(StandardCharsets.UTF_8));
            assertAuthenticated(component, "push-user", "push-password");

            publishCandidate(repository, "recovery", aclBytes("recovery-user", "recovery-password"));
            assertAuthenticated(component, "recovery-user", "recovery-password");
        } finally {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void concurrentRefUpdatesActivateTheAcceptedCandidate() throws Exception {
        OrionComponent component = component(configuration());
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            NativeGitRepository repository = repository(component);
            String alphaId = saveCandidate(repository, "alpha", aclBytes("alpha", "alpha-password"));
            String betaId = saveCandidate(repository, "beta", aclBytes("beta", "beta-password"));
            String expectedOldId = repository.refs().get(CONFIGURATION_REF);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<List<RefUpdateResult>> alpha = executor.submit(() -> {
                    start.await();
                    return publish(repository, expectedOldId, alphaId);
                });
                Future<List<RefUpdateResult>> beta = executor.submit(() -> {
                    start.await();
                    return publish(repository, expectedOldId, betaId);
                });
                start.countDown();
                assertThat(List.of(alpha.get().getFirst(), beta.get().getFirst()))
                        .containsExactlyInAnyOrder(RefUpdateResult.FAST_FORWARD, RefUpdateResult.STALE);
            }

            String activeId = repository.refs().get(CONFIGURATION_REF);
            if (alphaId.equals(activeId)) {
                assertAuthenticated(component, "alpha", "alpha-password");
            } else {
                assertThat(activeId).isEqualTo(betaId);
                assertAuthenticated(component, "beta", "beta-password");
            }
        } finally {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void enrollsSshKeysAtomicallyAndRetainsThemOnRestart() throws Exception {
        OrionConfiguration configuration = configuration();
        KeyPair firstKey = keyPair();
        KeyPair secondKey = keyPair();
        String firstOpenSshKey = PublicKeyEntry.toString(firstKey.getPublic());
        String secondOpenSshKey = PublicKeyEntry.toString(secondKey.getPublic());

        OrionComponent first = component(configuration);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            first.orionAccessControlService().createOrUpdateUser(user("alice"));

            assertThatThrownBy(() -> first.orionAccessControlService().addSshKeysToUser(
                    "alice",
                    List.of(firstOpenSshKey, "not a public key")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertSshAuthenticationFailed(first, "alice", firstKey);

            first.orionAccessControlService().addSshKeysToUser(
                    "alice",
                    List.of(
                            firstOpenSshKey + " alice@first",
                            KeyUtils.publicKeyToString(firstKey.getPublic()),
                            secondOpenSshKey));

            assertThat(first.orionAccessControlService().userExists("alice")).isTrue();
            assertThat(first.orionAccessControlService().userExists("missing")).isFalse();
            assertSshAuthenticated(first, "alice", firstKey);
            assertSshAuthenticated(first, "alice", secondKey);
            assertGitSshIdentity(first, firstKey, "alice");
            assertGitSshIdentity(first, secondKey, "alice");
            assertEnrolledKeys(first, "alice", firstOpenSshKey, secondOpenSshKey);
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        OrionComponent restarted = component(configuration);
        OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
        try {
            assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertSshAuthenticated(restarted, "alice", firstKey);
            assertSshAuthenticated(restarted, "alice", secondKey);
            assertGitSshIdentity(restarted, firstKey, "alice");
            assertEnrolledKeys(restarted, "alice", firstOpenSshKey, secondOpenSshKey);
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void rejectsUnknownUsersAndAmbiguousGitSshKeyOwnership() throws Exception {
        KeyPair sharedKey = keyPair();
        OrionComponent component = component(configuration());
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            component.orionAccessControlService().createOrUpdateUser(user("alice"));
            component.orionAccessControlService().createOrUpdateUser(user("bob"));
            component.orionAccessControlService().addSshKeysToUser(
                    "alice",
                    List.of(PublicKeyEntry.toString(sharedKey.getPublic())));
            component.orionAccessControlService().addSshKeysToUser(
                    "bob",
                    List.of(PublicKeyEntry.toString(sharedKey.getPublic())));

            assertSshAuthenticated(component, "alice", sharedKey);
            assertSshAuthenticated(component, "bob", sharedKey);
            assertSshAuthenticationFailed(component, "missing", sharedKey);
            assertThat(component.orionAccessControlService().authenticateGitSshKey(
                    sharedKey.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
        } finally {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    private OrionConfiguration configuration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("runtime").toString());
        configuration.getStorage().setLocation(tempDir.resolve("repositories").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation("local:" + REPOSITORY_NAME);
        configuration.getBootstrap().getAccessControl().setRef(CONFIGURATION_REF);
        configuration.getBootstrap().getAccessControl().setPaths(List.of(ACL_PATH));
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttp().setEnabled(false);
        configuration.getTransport().getHttps().setEnabled(false);
        return configuration;
    }

    private static OrionComponent component(OrionConfiguration configuration) {
        return component(configuration, ServerIdentityCapability.unavailable());
    }

    private static OrionComponent component(
            OrionConfiguration configuration,
            ServerIdentityCapability serverIdentity) {
        return DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .runtimeOptions(OrionRuntimeOptions.defaults())
                .serverIdentityCapability(serverIdentity)
                .build();
    }

    private static NativeGitRepository repository(OrionComponent component) {
        return component.nativeGitRepositoryProvider().find(REPOSITORY_NAME)
                .valueOrFailure("internal configuration repository");
    }

    private static void publishCandidate(
            NativeGitRepository repository,
            String candidateName,
            byte[] content) throws Exception {
        String candidateId = saveCandidate(repository, candidateName, content);
        String expectedOldId = repository.refs().get(CONFIGURATION_REF);
        assertThat(publish(repository, expectedOldId, candidateId))
                .containsExactly(RefUpdateResult.FAST_FORWARD);
    }

    private static String saveCandidate(
            NativeGitRepository repository,
            String candidateName,
            byte[] content) throws Exception {
        String candidateRef = "refs/heads/candidate-" + candidateName;
        repository.saveFiles(
                candidateRef,
                Map.of(ACL_PATH, content),
                "candidate " + candidateName,
                GitCommitAuthor.EMPTY);
        return repository.refs().get(candidateRef);
    }

    private static List<RefUpdateResult> publish(
            NativeGitRepository repository,
            String expectedOldId,
            String candidateId) {
        return repository.publishObjectsAndRefs(
                new LooseObjectStore(),
                List.of(new LooseRefStore.Update(
                        CONFIGURATION_REF,
                        expectedOldId,
                        candidateId)),
                true);
    }

    private static byte[] aclBytes(String userId, String password) throws Exception {
        OrionPasswordHashingService hashingService = new OrionPasswordHashingService();
        String hash = hashingService.calculateHash(PasswordHashingAlgorithm.SHA1, password.toCharArray());
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, hash));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XmlService().serialize(draft.toAccessControl(), output);
        return output.toByteArray();
    }

    private static AccessControlUserUpdate user(String userId) {
        return new AccessControlUserUpdate(userId, userId + "@example.test", List.of(), List.of());
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private record TestServerIdentity(
            KeyPair active,
            List<KeyPair> retained) implements ServerIdentityCapability {
        @Override
        public String activeKeyId() {
            return "active";
        }

        @Override
        public byte[] sign(byte[] payload) throws GeneralSecurityException {
            throw new GeneralSecurityException("Signing is not used by this test identity");
        }

        @Override
        public boolean hasVerificationKey(String keyId) {
            return false;
        }

        @Override
        public boolean verify(String keyId, byte[] payload, byte[] signature) {
            return false;
        }

        @Override
        public List<PublicKey> publicKeys() {
            return List.of(active.getPublic());
        }

        @Override
        public List<PublicKey> retainedPublicKeys() {
            List<PublicKey> publicKeys = new java.util.ArrayList<>();
            for (KeyPair keyPair : retained) {
                publicKeys.add(keyPair.getPublic());
            }
            return List.copyOf(publicKeys);
        }
    }

    private static void assertSshAuthenticated(OrionComponent component, String userId, KeyPair keyPair) {
        assertThat(component.orionAccessControlService().authenticateSshUser(
                userId,
                keyPair.getPublic().getEncoded()))
                .isInstanceOfSatisfying(AuthenticationResult.Success.class, success ->
                        assertThat(success.userIdentity().getUserId()).isEqualTo(userId));
    }

    private static void assertSshAuthenticationFailed(
            OrionComponent component,
            String userId,
            KeyPair keyPair) {
        assertThat(component.orionAccessControlService().authenticateSshUser(
                userId,
                keyPair.getPublic().getEncoded()))
                .isInstanceOf(AuthenticationResult.Failure.class);
    }

    private static void assertGitSshIdentity(OrionComponent component, KeyPair keyPair, String expectedUserId) {
        assertThat(component.orionAccessControlService()
                .authenticateGitSshKey(keyPair.getPublic().getEncoded()))
                .isInstanceOfSatisfying(AuthenticationResult.Success.class, success ->
                        assertThat(success.userIdentity().getUserId()).isEqualTo(expectedUserId));
    }

    private static void assertEnrolledKeys(
            OrionComponent component,
            String userId,
            String... expectedKeys) throws Exception {
        AccessControl accessControl = new XmlService().deserialize(new ByteArrayInputStream(
                component.orionAccessControlService().accessControlConfigurationFile()));
        AccessControl.User user = accessControl.getUsers().stream()
                .filter(candidate -> userId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(user.getCredentials())
                .filteredOn(credential ->
                        credential.getType() == AccessControl.CredentialType.OPENSSH_PUBLIC_KEY)
                .extracting(AccessControl.Credential::getValue)
                .containsExactlyInAnyOrder(expectedKeys);
    }

    private static void assertAuthenticated(OrionComponent component, String userId, String password) {
        assertThat(component.orionAccessControlService().authenticateUser(
                userId,
                password.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AuthenticationResult.Success.class);
    }
}
