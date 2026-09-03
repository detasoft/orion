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
import java.nio.file.Files;
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
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
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
    void resetFlagUsesDefaultCreationWhenTheAclIsMissing() throws Exception {
        OrionConfiguration configuration = configuration();
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        OrionComponent component = component(configuration, new OrionRuntimeOptions(true));
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            System.setOut(new PrintStream(processOutput, true, StandardCharsets.UTF_8));
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            char[] rootPassword = component.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create());
            assertAuthenticated(component, "root", new String(rootPassword));
        } finally {
            System.setOut(originalOut);
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        assertThat(processOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");
    }

    @Test
    void resetsExistingRootPasswordAndPreservesItsAccessAndSshKeys() throws Exception {
        OrionConfiguration configuration = configuration();
        KeyPair rootKey = keyPair();
        String rootOpenSshKey = PublicKeyEntry.toString(rootKey.getPublic());
        String oldPassword;
        String versionBeforeReset;
        AccessControl beforeReset;

        OrionComponent first = component(configuration);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            oldPassword = new String(first.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            first.orionAccessControlService().addKeyToUser("root", rootOpenSshKey);
            first.orionAccessControlService().createOrUpdateUser(user("alice"));
            AccessControlDraft draft = new XmlService().deserialize(new ByteArrayInputStream(
                    first.orionAccessControlService().accessControlConfigurationFile())).toDraft();
            AccessControlDraft.User root = draft.getUsers().stream()
                    .filter(candidate -> "root".equalsIgnoreCase(candidate.getId()))
                    .findFirst()
                    .orElseThrow();
            root.setFirst("Recovery");
            root.setLast("Administrator");
            root.setEmail("recovery-root@example.test");
            root.addCredential(
                    AccessControl.CredentialType.SHA1,
                    new OrionPasswordHashingService().calculateHash(
                            PasswordHashingAlgorithm.SHA1,
                            "legacy-root-password".toCharArray()));
            root.addCredential(
                    AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY,
                    "legacy-jwt-key",
                    "legacy-jwt-public-key");
            root.addGrant("ROOT_DIRECT")
                    .addKey(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING);
            first.orionAccessControlService().saveAccessControlConfigurationFile(
                    accessControlBytes(draft.toAccessControl()));
            beforeReset = new XmlService().deserialize(new ByteArrayInputStream(
                    first.orionAccessControlService().accessControlConfigurationFile()));
            versionBeforeReset = repository(first)
                    .loadFiles(CONFIGURATION_REF, List.of(ACL_PATH))
                    .version()
                    .orElseThrow();
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        ByteArrayOutputStream resetOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String newPassword;
        OrionComponent reset = component(configuration, new OrionRuntimeOptions(true));
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        try {
            System.setOut(new PrintStream(resetOutput, true, StandardCharsets.UTF_8));
            assertThat(resetLifecycle.runApplication()).isEqualTo(RUNNING);
            newPassword = new String(reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));

            assertThat(newPassword).isNotEqualTo(oldPassword);
            assertThat(reset.orionAccessControlService().authenticateUser(
                    "root",
                    oldPassword.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(reset.orionAccessControlService().authenticateUser(
                    "root",
                    "legacy-root-password".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertAuthenticated(reset, "root", newPassword);
            assertSshAuthenticated(reset, "root", rootKey);
            assertThat(reset.orionAccessControlService().userExists("alice")).isTrue();

            GitRepositoryFileSnapshot snapshot = repository(reset).loadFiles(
                    CONFIGURATION_REF,
                    List.of(ACL_PATH));
            assertThat(snapshot.version()).isPresent();
            assertThat(snapshot.version().orElseThrow()).isNotEqualTo(versionBeforeReset);
            AccessControl acl = new XmlService().deserialize(
                    new ByteArrayInputStream(snapshot.files().get(ACL_PATH)));
            AccessControl.User root = acl.getUsers().stream()
                    .filter(user -> "root".equalsIgnoreCase(user.getId()))
                    .findFirst()
                    .orElseThrow();
            AccessControl.User rootBeforeReset = beforeReset.getUsers().stream()
                    .filter(user -> "root".equalsIgnoreCase(user.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(root.getFirst()).isEqualTo(rootBeforeReset.getFirst());
            assertThat(root.getLast()).isEqualTo(rootBeforeReset.getLast());
            assertThat(root.getEmail()).isEqualTo(rootBeforeReset.getEmail());
            assertThat(root.getRoles()).containsExactly("ROOT");
            assertThat(root.getGrants()).containsExactlyElementsOf(rootBeforeReset.getGrants());
            assertThat(acl.getRoles()).containsExactlyElementsOf(beforeReset.getRoles());
            assertThat(acl.getGrants()).containsExactlyElementsOf(beforeReset.getGrants());
            assertThat(root.getCredentials())
                    .filteredOn(credential -> credential.getType() == AccessControl.CredentialType.ARGON2)
                    .hasSize(1);
            assertThat(root.getCredentials())
                    .filteredOn(credential -> credential.getType() == AccessControl.CredentialType.SHA1)
                    .isEmpty();
            assertThat(root.getCredentials())
                    .filteredOn(credential ->
                            credential.getType() == AccessControl.CredentialType.OPENSSH_PUBLIC_KEY)
                    .extracting(AccessControl.Credential::getValue)
                    .containsExactly(rootOpenSshKey);
            assertThat(root.getCredentials())
                    .filteredOn(credential ->
                            credential.getType() == AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY)
                    .containsExactly(new AccessControl.Credential(
                            AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY,
                            "legacy-jwt-key",
                            "legacy-jwt-public-key"));
        } finally {
            System.setOut(originalOut);
            assertThat(resetLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        assertThat(resetOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");

        OrionComponent restarted = component(configuration);
        OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
        try {
            assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertAuthenticated(restarted, "root", newPassword);
            assertSshAuthenticated(restarted, "root", rootKey);
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void recreatesMissingRootWithCanonicalFullPrivileges() throws Exception {
        OrionConfiguration configuration = configuration();
        OrionComponent first = component(configuration);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            first.orionAccessControlService().saveAccessControlConfigurationFile(missingRootAclBytes());
            assertThat(first.orionAccessControlService().userExists("root")).isFalse();
            assertAuthenticated(first, "alice", "alice-password");
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        ByteArrayOutputStream resetOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String newPassword;
        OrionComponent reset = component(configuration, new OrionRuntimeOptions(true));
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        try {
            System.setOut(new PrintStream(resetOutput, true, StandardCharsets.UTF_8));
            assertThat(resetLifecycle.runApplication()).isEqualTo(RUNNING);
            newPassword = new String(reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            assertAuthenticated(reset, "root", newPassword);
            assertAuthenticated(reset, "alice", "alice-password");

            AccessControl recovered = new XmlService().deserialize(new ByteArrayInputStream(
                    reset.orionAccessControlService().accessControlConfigurationFile()));
            assertThat(recovered.getUsers())
                    .extracting(AccessControl.User::getId)
                    .containsExactlyInAnyOrder("alice", "root");
            AccessControl.User root = recovered.getUsers().stream()
                    .filter(user -> "root".equalsIgnoreCase(user.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(root.getEmail()).isEqualTo("root@orion.pro");
            assertThat(root.getRoles()).containsExactly("ROOT");
            assertThat(root.getCredentials())
                    .extracting(AccessControl.Credential::getType)
                    .containsExactly(AccessControl.CredentialType.ARGON2);

            AccessControl canonical = ACLUtil.generateDefaultAccessControl(
                    "unused-password-hash",
                    AccessControl.CredentialType.ARGON2);
            for (AccessControl.Role expected : canonical.getRoles()) {
                assertThat(recovered.getRoles())
                        .filteredOn(role -> expected.getId().equals(role.getId()))
                        .singleElement()
                        .satisfies(actual -> assertThat(actual)
                                .usingRecursiveComparison()
                                .ignoringCollectionOrder()
                                .isEqualTo(expected));
            }
            for (AccessControl.Grant expected : canonical.getGrants()) {
                assertThat(recovered.getGrants())
                        .filteredOn(grant -> expected.getId().equals(grant.getId()))
                        .singleElement()
                        .satisfies(actual -> assertThat(actual)
                                .usingRecursiveComparison()
                                .ignoringCollectionOrder()
                                .isEqualTo(expected));
            }
            assertThat(recovered.getRoles())
                    .extracting(AccessControl.Role::getId)
                    .contains("ALICE");
            assertThat(recovered.getGrants())
                    .extracting(AccessControl.Grant::getId)
                    .contains("ALICE_READ");
        } finally {
            System.setOut(originalOut);
            assertThat(resetLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        assertThat(resetOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");

        OrionComponent restarted = component(configuration);
        OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
        try {
            assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertAuthenticated(restarted, "root", newPassword);
            assertAuthenticated(restarted, "alice", "alice-password");
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void resetsRootInItsConfiguredAclFileWithoutDuplicatingSecondaryEntries() throws Exception {
        String secondaryPath = "config/root.xml";
        OrionConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setPaths(List.of(ACL_PATH, secondaryPath));
        byte[] primaryAcl = aclBytes("alice", "alice-password");
        byte[] secondaryAcl = defaultAclBytes("old-root-password");
        OrionComponent reset = component(configuration, new OrionRuntimeOptions(true));
        NativeGitRepository repository = reset.nativeGitRepositoryProvider()
                .create(REPOSITORY_NAME)
                .valueOrFailure("configuration repository");
        repository.saveFiles(
                CONFIGURATION_REF,
                Map.of(ACL_PATH, primaryAcl, secondaryPath, secondaryAcl),
                "seed split ACL",
                GitCommitAuthor.EMPTY);

        OrionApplicationLifecycle lifecycle = reset.orionApplicationLifecycle();
        String newPassword;
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            newPassword = new String(reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            assertAuthenticated(reset, "root", newPassword);
            assertAuthenticated(reset, "alice", "alice-password");
            assertThat(reset.orionAccessControlService().authenticateUser(
                    "root",
                    "old-root-password".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);

            GitRepositoryFileSnapshot snapshot = repository.loadFiles(
                    CONFIGURATION_REF,
                    List.of(ACL_PATH, secondaryPath));
            assertThat(snapshot.files().get(ACL_PATH)).isEqualTo(primaryAcl);
            assertThat(usersAcross(snapshot, ACL_PATH, secondaryPath))
                    .extracting(AccessControl.User::getId)
                    .containsExactlyInAnyOrder("alice", "root");
        } finally {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void resetsRootPasswordThroughFileAclStorage() throws Exception {
        Path aclDirectory = tempDir.resolve("file-acl");
        Path aclFile = aclDirectory.resolve(ACL_PATH);
        Files.createDirectories(aclFile.getParent());
        Files.write(aclFile, defaultAclBytes("old-root-password"));
        OrionConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setLocation(aclDirectory.toUri().toString());
        OrionComponent reset = component(configuration, new OrionRuntimeOptions(true));
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        String newPassword;
        try {
            assertThat(resetLifecycle.runApplication()).isEqualTo(RUNNING);
            newPassword = new String(reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            assertAuthenticated(reset, "root", newPassword);
            assertThat(reset.orionAccessControlService().authenticateUser(
                    "root",
                    "old-root-password".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
        } finally {
            assertThat(resetLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        OrionComponent restarted = component(configuration);
        OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
        try {
            assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertAuthenticated(restarted, "root", newPassword);
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void refusesToResetAmbiguousRootUsersWithoutPersistingOrPrintingPassword() throws Exception {
        OrionConfiguration configuration = configuration();
        OrionComponent first = component(configuration);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        String versionBeforeReset;
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            repository(first).saveFiles(
                    CONFIGURATION_REF,
                    Map.of(ACL_PATH, duplicateRootAclBytes()),
                    "seed ambiguous root ACL",
                    GitCommitAuthor.EMPTY);
            versionBeforeReset = repository(first)
                    .loadFiles(CONFIGURATION_REF, List.of(ACL_PATH))
                    .version()
                    .orElseThrow();
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        ByteArrayOutputStream resetOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        OrionComponent reset = component(configuration, new OrionRuntimeOptions(true));
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        try {
            System.setOut(new PrintStream(resetOutput, true, StandardCharsets.UTF_8));
            assertThat(resetLifecycle.runApplication()).isEqualTo(ERR);
            assertThat(reset.runtimeStateMachine().childStatuses().get("transports").state())
                    .isEqualTo(NEW);
            assertThat(repository(reset).loadFiles(CONFIGURATION_REF, List.of(ACL_PATH)).version())
                    .contains(versionBeforeReset);
        } finally {
            System.setOut(originalOut);
            assertThat(resetLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        assertThat(resetOutput.toString(StandardCharsets.UTF_8)).doesNotContain("---ROOT PASSWORD: ");
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
            OrionRuntimeOptions runtimeOptions) {
        return component(configuration, runtimeOptions, ServerIdentityCapability.unavailable());
    }

    private static OrionComponent component(
            OrionConfiguration configuration,
            ServerIdentityCapability serverIdentity) {
        return component(configuration, OrionRuntimeOptions.defaults(), serverIdentity);
    }

    private static OrionComponent component(
            OrionConfiguration configuration,
            OrionRuntimeOptions runtimeOptions,
            ServerIdentityCapability serverIdentity) {
        return DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .runtimeOptions(runtimeOptions)
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
        return accessControlBytes(draft.toAccessControl());
    }

    private static byte[] missingRootAclBytes() throws Exception {
        OrionPasswordHashingService hashingService = new OrionPasswordHashingService();
        String hash = hashingService.calculateHash(
                PasswordHashingAlgorithm.SHA1,
                "alice-password".toCharArray());
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser("alice", "alice@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, hash)
                .addRole("ALICE"));
        draft.getRoles().add(ACLUtil.createRole("ALICE").addGrantReference("ALICE_READ"));
        draft.getGrants().add(ACLUtil.createGrant("ALICE_READ")
                .addKey(AccessControl.GrantKey.REPOSITORY, "alice/*")
                .addKey(AccessControl.GrantKey.READ, "true"));
        draft.getRoles().add(ACLUtil.createRole("ROOT").addGrantReference("APPLICATION_CONTROL"));
        draft.getGrants().add(ACLUtil.createGrant("CONNECT")
                .addKey(AccessControl.GrantKey.NETWORK_SOURCE, "192.0.2.1"));
        draft.getGrants().add(ACLUtil.createGrant("ALL_REPOSITORY")
                .addKey(AccessControl.GrantKey.REPOSITORY, "restricted")
                .addKey(AccessControl.GrantKey.READ, "false"));
        draft.getGrants().add(ACLUtil.createGrant("APPLICATION_CONTROL")
                .addKey(AccessControl.GrantKey.ADMIN, "false"));
        return accessControlBytes(draft.toAccessControl());
    }

    private static byte[] duplicateRootAclBytes() throws Exception {
        String xml = """
                <AccessControl schemaVersion="1">
                  <users>
                    <user>
                      <id>root</id>
                      <email>first-root@example.test</email>
                      <credentials>
                        <credential>
                          <type>SHA1</type>
                          <value>first-hash</value>
                        </credential>
                      </credentials>
                    </user>
                    <user>
                      <id>ROOT</id>
                      <email>second-root@example.test</email>
                      <credentials>
                        <credential>
                          <type>SHA1</type>
                          <value>second-hash</value>
                        </credential>
                      </credentials>
                    </user>
                  </users>
                </AccessControl>
                """;
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] defaultAclBytes(String password) throws Exception {
        OrionPasswordHashingService hashingService = new OrionPasswordHashingService();
        String hash = hashingService.calculateHash(
                PasswordHashingAlgorithm.SHA1,
                password.toCharArray());
        return accessControlBytes(ACLUtil.generateDefaultAccessControl(
                hash,
                AccessControl.CredentialType.SHA1));
    }

    private static List<AccessControl.User> usersAcross(
            GitRepositoryFileSnapshot snapshot,
            String... paths) throws Exception {
        List<AccessControl.User> users = new java.util.ArrayList<>();
        XmlService xmlService = new XmlService();
        for (String path : paths) {
            AccessControl acl = xmlService.deserialize(new ByteArrayInputStream(snapshot.files().get(path)));
            users.addAll(acl.getUsers());
        }
        return List.copyOf(users);
    }

    private static byte[] accessControlBytes(AccessControl accessControl) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XmlService().serialize(accessControl, output);
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
