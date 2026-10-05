package pro.deta.orion.component;

import pro.deta.orion.config.OrionConfigurationEdit;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.SshKeyEnrollmentResult;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.git.s3.S3Transport;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.KeyMaterialAdministrationCapability;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.keymaterial.SshHostKeyCapability;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.auth.DefaultAccessControl;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.Role;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.acl.RootPasswordReset;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

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
import java.security.Signature;
import java.util.ArrayList;
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

class InternalConfigurationRepositoryLifecycleIT {
    private static final String REPOSITORY_NAME = "internal/configuration";
    private static final String CONFIGURATION_REF = "refs/heads/configuration";
    private static final String ACL_PATH = "config/orion.xml";

    @TempDir
    private Path tempDir;

    private static void updateUser(OrionComponent component, AccessControlUserUpdate user) {
        try (OrionConfigurationEdit edit = component.configurationEditor().edit()) {
            component.orionAccessControlService().createOrUpdateUser(edit, user);
            edit.apply("createOrUpdateUser() " + user.id(), new UserEmail(user.id(), user.email()));
        }
    }

    private static SshKeyEnrollmentResult enroll(
            OrionComponent component, String generation, List<String> keys) {
        try (OrionConfigurationEdit edit = component.configurationEditor().edit()) {
            SshKeyEnrollmentResult result =
                    component.orionAccessControlService().completeRootSshKeyEnrollment(edit, generation, keys);
            if (result instanceof SshKeyEnrollmentResult.Success) {
                edit.apply("complete root SSH key enrollment", UserEmail.EMPTY);
            }
            return result;
        }
    }

    private static SshCredentialUpdateResult addKeys(
            OrionComponent component, String user, List<String> keys) {
        return mutateKeys(component, edit -> component.orionAccessControlService().addSshCredentials(edit, user, keys));
    }

    private static void addKey(OrionComponent component, String user, String key) {
        addKeysOrThrow(component, user, List.of(key));
    }

    private static void addKeysOrThrow(OrionComponent component, String user, List<String> keys) {
        if (addKeys(component, user, keys) instanceof SshCredentialUpdateResult.Failure failure) {
            throw new IllegalArgumentException(failure.reason(), failure.throwable());
        }
    }

    private static SshCredentialUpdateResult removeKey(
            OrionComponent component, String user, String prefix, boolean force) {
        return mutateKeys(component,
                edit -> component.orionAccessControlService().removeSshCredential(edit, user, prefix, force));
    }

    private static SshCredentialUpdateResult mutateKeys(OrionComponent component,
            java.util.function.Function<OrionConfigurationEdit,
                    SshCredentialUpdateResult> operation) {
        try (OrionConfigurationEdit edit = component.configurationEditor().edit()) {
            SshCredentialUpdateResult result = operation.apply(edit);
            if (result instanceof SshCredentialUpdateResult.Success success && success.changed()) {
                edit.apply("update test SSH credentials", UserEmail.EMPTY);
            }
            return result;
        }
    }

    @Test
    void bootstrapsOnceAndReusesTheCommittedAclOnRestart() throws Exception {
        BootstrapConfiguration configuration = configuration();
        KeyPair enrolledKey = keyPair();
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
                assertRecoveryPasswordOnly(first, new String(rootPassword));
                assertThat(first.orionAccessControlService().authenticateUserAndIssueToken(
                        "root", new String(rootPassword).getBytes(StandardCharsets.UTF_8), 600))
                        .isInstanceOf(TokenIssueResult.Failure.class);
                assertThat(first.nativeGitRepositoryProvider().repositoryNames())
                        .containsExactly(REPOSITORY_NAME);
                byte[] content = repository(first).files().readBytes(CONFIGURATION_REF, ACL_PATH);
                firstVersion = repository(first).refs().get(CONFIGURATION_REF);
                AccessControl acl = OrionXml.read(
                        new ByteArrayInputStream(content))
                                .system().accessControl();
                assertThat(acl.users()).extracting(User::id).contains("root");
            } finally {
                assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
            }

            OrionComponent restarted = component(configuration);
            OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
            try {
                assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
                assertRecoveryPasswordOnly(restarted, new String(rootPassword));
                assertThat(repository(restarted).refs().get(CONFIGURATION_REF)).isEqualTo(firstVersion);
                assertThatThrownBy(() -> restarted.orionAccessControlService()
                        .plainRootToken(PlainRootTokenAccessForTests.create()))
                        .isInstanceOf(IllegalStateException.class);
                SshKeyEnrollmentAuthentication.Success enrollment = (SshKeyEnrollmentAuthentication.Success)
                        restarted.orionAccessControlService().authenticateSshKeyEnrollment(
                                "root", new String(rootPassword).getBytes(StandardCharsets.UTF_8));
                String generation = enrollment.rootRecoveryGeneration().orElseThrow();
                assertThat(enroll(restarted, generation, List.of(PublicKeyEntry.toString(enrolledKey.getPublic()))))
                        .isInstanceOf(SshKeyEnrollmentResult.Success.class);
                assertThat(restarted.orionAccessControlService().authenticateSshKeyEnrollment(
                        "root", new String(rootPassword).getBytes(StandardCharsets.UTF_8)))
                        .isInstanceOf(SshKeyEnrollmentAuthentication.Failure.class);
                assertThat(enroll(restarted, generation, List.of(PublicKeyEntry.toString(keyPair().getPublic()))))
                        .isInstanceOf(SshKeyEnrollmentResult.Failure.class);
                assertSshAuthenticated(restarted, "root", enrolledKey);
            } finally {
                assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
            }
        } finally {
            System.setOut(originalOut);
        }

        assertThat(processOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");
        OrionComponent enrolled = component(configuration);
        OrionApplicationLifecycle enrolledLifecycle = enrolled.orionApplicationLifecycle();
        try {
            assertThat(enrolledLifecycle.runApplication()).isEqualTo(RUNNING);
            assertSshAuthenticated(enrolled, "root", enrolledKey);
            assertThat(enrolled.orionAccessControlService().authenticateSshKeyEnrollment(
                    "root", new String(rootPassword).getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(SshKeyEnrollmentAuthentication.Failure.class);
        } finally {
            assertThat(enrolledLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void resetFlagCreatesARecoveryRootWhenTheAclIsMissing() throws Exception {
        BootstrapConfiguration configuration = configuration();
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        OrionComponent component = component(configuration, new RootPasswordReset(true));
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            System.setOut(new PrintStream(processOutput, true, StandardCharsets.UTF_8));
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            char[] rootPassword = component.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create());
            assertRecoveryPasswordOnly(component, new String(rootPassword));
            AccessControl acl = OrionXml.read(new ByteArrayInputStream(
                    component.orionAccessControlService().accessControlConfigurationFile().content()))
                            .system().accessControl();
            assertThat(acl.users().getFirst().credentials())
                    .singleElement()
                    .satisfies(credential -> assertThat(credential.keyId())
                            .startsWith("root-auth-generation:"));
        } finally {
            System.setOut(originalOut);
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        assertThat(processOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");
    }

    @Test
    void recreatesExistingRootAsCanonicalRecoveryIdentity() throws Exception {
        BootstrapConfiguration configuration = configuration();
        KeyPair rootKey = keyPair();
        KeyPair recoveredRootKey = keyPair();
        KeyPair aliceKey = keyPair();
        TestServerIdentity serverIdentity = new TestServerIdentity(keyPair(), List.of());
        String rootOpenSshKey = PublicKeyEntry.toString(rootKey.getPublic());
        String oldPassword;
        String oldRootToken;
        String aliceToken;
        String versionBeforeReset;
        AccessControl beforeReset;

        OrionComponent first = component(configuration, serverIdentity);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            oldPassword = new String(first.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            SshKeyEnrollmentAuthentication.Success enrollment = (SshKeyEnrollmentAuthentication.Success)
                    first.orionAccessControlService().authenticateSshKeyEnrollment(
                            "root", oldPassword.getBytes(StandardCharsets.UTF_8));
            assertThat(enroll(first, enrollment.rootRecoveryGeneration().orElseThrow(), List.of(rootOpenSshKey)))
                    .isInstanceOf(SshKeyEnrollmentResult.Success.class);
            updateUser(first, user("alice"));
            addKey(first, "alice", PublicKeyEntry.toString(aliceKey.getPublic()));
            oldRootToken = issueTokenForSshKey(first, "root", rootKey);
            aliceToken = issueTokenForSshKey(first, "alice", aliceKey);
            AccessControl acl = OrionXml.read(new ByteArrayInputStream(
                    first.orionAccessControlService().accessControlConfigurationFile().content()))
                            .system().accessControl();
            User root = acl.users().stream()
                    .filter(candidate -> "root".equalsIgnoreCase(candidate.id()))
                    .findFirst()
                    .orElseThrow();
            List<Credential> credentials = new ArrayList<>(root.credentials());
            credentials.add(new Credential(AccessControl.CredentialType.SHA1,
                    new OrionPasswordHashingService().calculateHash(
                            PasswordHashingAlgorithm.SHA1,
                            "legacy-root-password".toCharArray())));
            credentials.add(new Credential(
                    AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY,
                    "legacy-jwt-key",
                    "legacy-jwt-public-key"));
            List<Grant> grants = new ArrayList<>(root.grants());
            grants.add(new Grant("ROOT_DIRECT", List.of(
                    new GrantExpression(
                            AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING))));
            User updatedRoot = new User(root.id(), "Recovery", "Administrator",
                    "recovery-root@example.test", credentials, root.roles(), grants);
            List<User> users = new ArrayList<>(acl.users());
            users.set(users.indexOf(root), updatedRoot);
            OrionDocument replacement = OrionXml.read(
                    new ByteArrayInputStream(accessControlBytes(
                            new AccessControl(users, acl.roles(), acl.grants()))));
            first.configurationEditor().edit(first.orionAccessControlService()
                    .accessControlConfigurationFile().revision().orElseThrow())
                    .update(ignored -> replacement).apply("Prepare configuration", UserEmail.EMPTY);
            beforeReset = OrionXml.read(new ByteArrayInputStream(
                    first.orionAccessControlService().accessControlConfigurationFile().content()))
                            .system().accessControl();
            versionBeforeReset = repository(first).refs().get(CONFIGURATION_REF);
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        ByteArrayOutputStream resetOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String newPassword;
        OrionComponent reset = component(configuration, new RootPasswordReset(true), serverIdentity);
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
            assertThat(reset.orionAccessControlService().authenticateUser(
                    "root", newPassword.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertSshAuthenticationFailed(reset, "root", rootKey);
            assertSshAuthenticated(reset, "alice", aliceKey);
            assertThat(reset.orionAccessControlService().verifyToken(
                    oldRootToken.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);
            assertThat(reset.orionAccessControlService().verifyToken(
                    aliceToken.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Success.class);
            assertThat(reset.orionAccessControlService().userExists("alice")).isTrue();

            byte[] content = repository(reset).files().readBytes(CONFIGURATION_REF, ACL_PATH);
            assertThat(repository(reset).refs().get(CONFIGURATION_REF)).isNotNull().isNotEqualTo(versionBeforeReset);
            AccessControl acl = OrionXml.read(
                    new ByteArrayInputStream(content))
                            .system().accessControl();
            User root = acl.users().stream()
                    .filter(user -> "root".equalsIgnoreCase(user.id()))
                    .findFirst()
                    .orElseThrow();
            AccessControl canonical = DefaultAccessControl.create("unused-password-hash");
            assertThat(root.first()).isNull();
            assertThat(root.last()).isNull();
            assertThat(root.email()).isEqualTo("root@orion.pro");
            assertThat(root.roles()).containsExactly("ROOT");
            assertThat(root.grants()).isEmpty();
            assertThat(acl.roles()).hasSameSizeAs(canonical.roles());
            for (Role expected : canonical.roles()) {
                assertThat(acl.roles())
                        .filteredOn(role -> expected.id().equals(role.id()))
                        .singleElement()
                        .satisfies(actual -> assertThat(actual)
                                .usingRecursiveComparison()
                                .ignoringCollectionOrder()
                                .isEqualTo(expected));
            }
            assertThat(acl.grants()).hasSameSizeAs(canonical.grants());
            for (Grant expected : canonical.grants()) {
                assertThat(acl.grants())
                        .filteredOn(grant -> expected.id().equals(grant.id()))
                        .singleElement()
                        .satisfies(actual -> assertThat(actual)
                                .usingRecursiveComparison()
                                .ignoringCollectionOrder()
                                .isEqualTo(expected));
            }
            assertThat(root.credentials())
                    .filteredOn(credential -> credential.type() == AccessControl.CredentialType.ARGON2)
                    .singleElement()
                    .satisfies(credential -> assertThat(credential.keyId())
                            .startsWith("root-auth-generation:"));
            assertThat(root.credentials()).hasSize(1);

            assertThat(reset.orionAccessControlService().authenticateUserAndIssueToken(
                    "root", newPassword.getBytes(StandardCharsets.UTF_8), 600))
                    .isInstanceOf(pro.deta.orion.auth.TokenIssueResult.Failure.class);
            SshKeyEnrollmentAuthentication enrollment = reset.orionAccessControlService()
                    .authenticateSshKeyEnrollment(
                            "root", newPassword.getBytes(StandardCharsets.UTF_8));
            assertThat(enrollment).isInstanceOf(SshKeyEnrollmentAuthentication.Success.class);
            String recoveryGeneration = ((SshKeyEnrollmentAuthentication.Success) enrollment)
                    .rootRecoveryGeneration()
                    .orElseThrow();
            assertThat(enroll(reset, recoveryGeneration, List.of("invalid public key")))
                    .isInstanceOf(SshKeyEnrollmentResult.Failure.class);
            assertThat(reset.orionAccessControlService().authenticateSshKeyEnrollment(
                    "root", newPassword.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(SshKeyEnrollmentAuthentication.Success.class);
            assertThat(enroll(reset, recoveryGeneration, List.of(PublicKeyEntry.toString(recoveredRootKey.getPublic()))))
                    .isInstanceOf(SshKeyEnrollmentResult.Success.class);
            assertThat(reset.orionAccessControlService().authenticateUser(
                    "root", newPassword.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertSshAuthenticated(reset, "root", recoveredRootKey);
            String newRootToken = issueTokenForSshKey(reset, "root", recoveredRootKey);
            assertThat(reset.orionAccessControlService().verifyToken(
                    newRootToken.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Success.class);
            assertThat(enroll(reset, "stale-generation", List.of(PublicKeyEntry.toString(rootKey.getPublic()))))
                    .isInstanceOf(SshKeyEnrollmentResult.Failure.class);
        } finally {
            System.setOut(originalOut);
            assertThat(resetLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        assertThat(resetOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");

        OrionComponent restarted = component(configuration, serverIdentity);
        OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
        try {
            assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertThat(restarted.orionAccessControlService().authenticateUser(
                    "root", newPassword.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertSshAuthenticationFailed(restarted, "root", rootKey);
            assertSshAuthenticated(restarted, "root", recoveredRootKey);
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void recreatesMissingRootWithCanonicalFullPrivileges() throws Exception {
        BootstrapConfiguration configuration = configuration();
        OrionComponent first = component(configuration);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            OrionDocument replacement = OrionXml.read(new ByteArrayInputStream(missingRootAclBytes()));
            first.configurationEditor().edit(first.orionAccessControlService()
                    .accessControlConfigurationFile().revision().orElseThrow())
                    .update(ignored -> replacement).apply("Prepare configuration", UserEmail.EMPTY);
            assertThat(first.orionAccessControlService().userExists("root")).isFalse();
            assertAuthenticated(first, "alice", "alice-password");
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        ByteArrayOutputStream resetOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String newPassword;
        OrionComponent reset = component(configuration, new RootPasswordReset(true));
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        try {
            System.setOut(new PrintStream(resetOutput, true, StandardCharsets.UTF_8));
            assertThat(resetLifecycle.runApplication()).isEqualTo(RUNNING);
            newPassword = new String(reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            assertRecoveryPasswordOnly(reset, newPassword);
            assertAuthenticated(reset, "alice", "alice-password");

            AccessControl recovered = OrionXml.read(new ByteArrayInputStream(
                    reset.orionAccessControlService().accessControlConfigurationFile().content()))
                            .system().accessControl();
            assertThat(recovered.users())
                    .extracting(User::id)
                    .containsExactlyInAnyOrder("alice", "root");
            User root = recovered.users().stream()
                    .filter(user -> "root".equalsIgnoreCase(user.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(root.email()).isEqualTo("root@orion.pro");
            assertThat(root.roles()).containsExactly("ROOT");
            assertThat(root.credentials())
                    .extracting(Credential::type)
                    .containsExactly(AccessControl.CredentialType.ARGON2);

            AccessControl canonical = DefaultAccessControl.create(
                    "unused-password-hash",
                    AccessControl.CredentialType.ARGON2);
            for (Role expected : canonical.roles()) {
                assertThat(recovered.roles())
                        .filteredOn(role -> expected.id().equals(role.id()))
                        .singleElement()
                        .satisfies(actual -> assertThat(actual)
                                .usingRecursiveComparison()
                                .ignoringCollectionOrder()
                                .isEqualTo(expected));
            }
            for (Grant expected : canonical.grants()) {
                assertThat(recovered.grants())
                        .filteredOn(grant -> expected.id().equals(grant.id()))
                        .singleElement()
                        .satisfies(actual -> assertThat(actual)
                                .usingRecursiveComparison()
                                .ignoringCollectionOrder()
                                .isEqualTo(expected));
            }
            assertThat(recovered.roles())
                    .extracting(Role::id)
                    .contains("ALICE");
            assertThat(recovered.grants())
                    .extracting(Grant::id)
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
            assertRecoveryPasswordOnly(restarted, newPassword);
            assertAuthenticated(restarted, "alice", "alice-password");
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void recoversRootInTheConfiguredFileAndPreservesOtherFiles() throws Exception {
        String secondaryPath = "config/root.xml";
        BootstrapConfiguration configuration = configuration();
        byte[] primaryAcl = aclBytes("alice", "alice-password");
        byte[] secondaryAcl = defaultAclBytes("old-root-password");
        OrionComponent reset = component(configuration, new RootPasswordReset(true));
        NativeGitRepository repository = reset.nativeGitRepositoryProvider()
                .openForWrite(REPOSITORY_NAME)
                .valueOrFailure("configuration repository");
        repository.files().withAccess(CONFIGURATION_REF, "seed configuration and unrelated XML", GitCommitAuthor.EMPTY,
                fileAccess -> {
            fileAccess.write(ACL_PATH, primaryAcl);
            fileAccess.write(secondaryPath, secondaryAcl);
            fileAccess.apply();
            return null;
        });

        OrionApplicationLifecycle lifecycle = reset.orionApplicationLifecycle();
        String newPassword;
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            newPassword = new String(reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            assertRecoveryPasswordOnly(reset, newPassword);
            assertAuthenticated(reset, "alice", "alice-password");
            assertThat(reset.orionAccessControlService().authenticateUser(
                    "root",
                    "old-root-password".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);

            Map<String, byte[]> snapshot = Map.of(ACL_PATH, repository.files().readBytes(CONFIGURATION_REF,
                    ACL_PATH), secondaryPath, repository.files().readBytes(CONFIGURATION_REF, secondaryPath));
            AccessControl primary = OrionXml.read(
                    new ByteArrayInputStream(snapshot.get(ACL_PATH)))
                            .system().accessControl();
            assertThat(primary.users()).extracting(User::id)
                    .containsExactlyInAnyOrder("alice", "root");
            assertThat(snapshot.get(secondaryPath)).containsExactly(secondaryAcl);
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
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getAccessControl().setLocation(aclDirectory.toUri().toString());
        OrionComponent reset = component(configuration, new RootPasswordReset(true));
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        String newPassword;
        try {
            assertThat(resetLifecycle.runApplication()).isEqualTo(RUNNING);
            newPassword = new String(reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create()));
            assertRecoveryPasswordOnly(reset, newPassword);
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
            assertRecoveryPasswordOnly(restarted, newPassword);
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void replacesNoncanonicalRootWithOneCanonicalRecoveryRoot() throws Exception {
        BootstrapConfiguration configuration = configuration();
        OrionComponent first = component(configuration);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        String versionBeforeReset;
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            repository(first).files().withAccess(CONFIGURATION_REF, "seed noncanonical root ACL",
                    GitCommitAuthor.EMPTY, fileAccess -> {
                fileAccess.write(ACL_PATH, noncanonicalRootAclBytes());
                fileAccess.apply();
                return null;
            });
            versionBeforeReset = repository(first).refs().get(CONFIGURATION_REF);
        } finally {
            assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        ByteArrayOutputStream resetOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        OrionComponent reset = component(configuration, new RootPasswordReset(true));
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        try {
            System.setOut(new PrintStream(resetOutput, true, StandardCharsets.UTF_8));
            assertThat(resetLifecycle.runApplication()).isEqualTo(RUNNING);
            assertThat(repository(reset).refs().get(CONFIGURATION_REF)).isNotEqualTo(versionBeforeReset);
            AccessControl recovered = OrionXml.read(new ByteArrayInputStream(
                    reset.orionAccessControlService().accessControlConfigurationFile().content()))
                            .system().accessControl();
            assertThat(recovered.users())
                    .filteredOn(user -> "root".equalsIgnoreCase(user.id()))
                    .extracting(User::id)
                    .containsExactly("root");
            assertThat(recovered.users().getFirst().credentials())
                    .noneMatch(credential -> credential.value().equals("old-hash"));
        } finally {
            System.setOut(originalOut);
            assertThat(resetLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        assertThat(resetOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");
    }

    @Test
    void rotationRevokesRetainedServerIdentityFromRootSsh() throws Exception {
        BootstrapConfiguration configuration = configuration();
        KeyPair oldIdentity = keyPair();
        KeyPair activeIdentity = keyPair();

        OrionComponent first = component(configuration, new TestServerIdentity(oldIdentity, List.of()));
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            OrionDocument replacement = OrionXml.read(new ByteArrayInputStream(defaultAclBytes("legacy-password")));
            first.configurationEditor().edit(first.orionAccessControlService()
                    .accessControlConfigurationFile().revision().orElseThrow())
                    .update(ignored -> replacement).apply("Prepare configuration", UserEmail.EMPTY);
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
        BootstrapConfiguration configuration = configuration();
        OrionComponent component = component(configuration);
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            NativeGitRepository repository = repository(component);

            String validCommit = publishCandidate(repository, "push", aclBytes("push-user", "push-password"));
            assertAuthenticated(component, "push-user", "push-password");

            publishCandidate(repository, "invalid", "<not-valid-xml".getBytes(StandardCharsets.UTF_8));
            assertAuthenticated(component, "push-user", "push-password");

            publishCandidate(repository, "recovery", aclBytes("recovery-user", "recovery-password"));
            assertAuthenticated(component, "recovery-user", "recovery-password");

            assertThat(publish(repository, repository.refs().get(CONFIGURATION_REF), validCommit))
                    .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
            assertAuthenticated(component, "push-user", "push-password");
        } finally {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        OrionComponent restarted = component(configuration);
        OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
        try {
            assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertAuthenticated(restarted, "push-user", "push-password");
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
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
                        .extracting(RefUpdateResult::status)
                        .containsExactlyInAnyOrder(RefUpdateResult.Status.APPLIED, RefUpdateResult.Status.EXPECTED_OLD_MISMATCH);
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
        BootstrapConfiguration configuration = configuration();
        KeyPair firstKey = keyPair();
        KeyPair secondKey = keyPair();
        String firstOpenSshKey = PublicKeyEntry.toString(firstKey.getPublic());
        String secondOpenSshKey = PublicKeyEntry.toString(secondKey.getPublic());

        OrionComponent first = component(configuration);
        OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
        try {
            assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
            updateUser(first, user("alice"));

            assertThatThrownBy(() -> addKeysOrThrow(first, "alice", List.of(firstOpenSshKey, "not a public key")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertSshAuthenticationFailed(first, "alice", firstKey);

            addKeysOrThrow(first, "alice", List.of(
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
    void forcedLastRootKeyRemovalRemainsLockedUntilExplicitReset() throws Exception {
        BootstrapConfiguration configuration = configuration();
        TestServerIdentity serverIdentity = new TestServerIdentity(keyPair(), List.of());
        KeyPair rootKey = keyPair();
        KeyPair aliceKey = keyPair();
        String rootToken;
        String aliceToken;

        OrionComponent initial = component(configuration, new RootPasswordReset(true), serverIdentity);
        OrionApplicationLifecycle initialLifecycle = initial.orionApplicationLifecycle();
        try {
            assertThat(initialLifecycle.runApplication()).isEqualTo(RUNNING);
            char[] recoveryPassword = initial.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create());
            SshKeyEnrollmentAuthentication enrollment = initial.orionAccessControlService()
                    .authenticateSshKeyEnrollment(
                            "root",
                            new String(recoveryPassword).getBytes(StandardCharsets.UTF_8));
            String generation = ((SshKeyEnrollmentAuthentication.Success) enrollment)
                    .rootRecoveryGeneration()
                    .orElseThrow();
            assertThat(enroll(initial, generation, List.of(PublicKeyEntry.toString(rootKey.getPublic()))))
                    .isInstanceOf(SshKeyEnrollmentResult.Success.class);
            updateUser(initial, user("alice"));
            addKey(initial, "alice", PublicKeyEntry.toString(aliceKey.getPublic()));
            rootToken = issueTokenForSshKey(initial, "root", rootKey);
            aliceToken = issueTokenForSshKey(initial, "alice", aliceKey);

            String rootFingerprint = org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(rootKey.getPublic());
            assertThat(removeKey(initial, "root", rootFingerprint, true))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertSshAuthenticationFailed(initial, "root", rootKey);
            assertThat(initial.orionAccessControlService().verifyToken(
                    rootToken.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);
        } finally {
            assertThat(initialLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        OrionComponent restarted = component(configuration, serverIdentity);
        OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
        try {
            assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
            assertSshAuthenticationFailed(restarted, "root", rootKey);
            assertSshAuthenticated(restarted, "alice", aliceKey);
            assertThat(restarted.orionAccessControlService().verifyToken(
                    rootToken.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);
            assertThat(restarted.orionAccessControlService().verifyToken(
                    aliceToken.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Success.class);
            assertThat(addKeys(restarted, "root", List.of(PublicKeyEntry.toString(keyPair().getPublic()))))
                    .isInstanceOfSatisfying(
                            SshCredentialUpdateResult.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(SshCredentialFailureCode.ROOT_LOCKED));
        } finally {
            assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
        }

        OrionComponent reset = component(configuration, new RootPasswordReset(true), serverIdentity);
        OrionApplicationLifecycle resetLifecycle = reset.orionApplicationLifecycle();
        try {
            assertThat(resetLifecycle.runApplication()).isEqualTo(RUNNING);
            char[] password = reset.orionAccessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create());
            assertRecoveryPasswordOnly(reset, new String(password));
            assertSshAuthenticationFailed(reset, "root", rootKey);
            assertSshAuthenticated(reset, "alice", aliceKey);
        } finally {
            assertThat(resetLifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void rejectsUnknownUsersAndAmbiguousGitSshKeyOwnership() throws Exception {
        KeyPair sharedKey = keyPair();
        OrionComponent component = component(configuration());
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            updateUser(component, user("alice"));
            updateUser(component, user("bob"));
            addKeysOrThrow(component, "alice", List.of(PublicKeyEntry.toString(sharedKey.getPublic())));
            addKeysOrThrow(component, "bob", List.of(PublicKeyEntry.toString(sharedKey.getPublic())));

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

    private BootstrapConfiguration configuration() {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("runtime").toString());
        configuration.getStorage().setLocation(tempDir.resolve("repositories").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation("local:" + REPOSITORY_NAME);
        configuration.getBootstrap().getAccessControl().setRef(CONFIGURATION_REF);
        configuration.getBootstrap().getAccessControl().setPath(ACL_PATH);
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttp().setEnabled(false);
        return configuration;
    }

    private static OrionComponent component(BootstrapConfiguration configuration) {
        return component(configuration, ServerIdentityCapability.unavailable());
    }

    private static OrionComponent component(
            BootstrapConfiguration configuration,
            RootPasswordReset rootPasswordReset) {
        return component(configuration, rootPasswordReset, ServerIdentityCapability.unavailable());
    }

    private static OrionComponent component(
            BootstrapConfiguration configuration,
            ServerIdentityCapability serverIdentity) {
        return component(configuration, new RootPasswordReset(false), serverIdentity);
    }

    private static OrionComponent component(
            BootstrapConfiguration configuration,
            RootPasswordReset rootPasswordReset,
            ServerIdentityCapability serverIdentity) {
        pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend backend =
                pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend.file(
                        new ConfigurationContext(configuration).getFileGitStoragePath());
        S3Transport transport = new S3Transport();
        pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory configured =
                new pro.deta.orion.git.s3.ConfiguredNativeGitRepositoryFactory(backend, transport);
        NativeGitRepositoryFactory provider = new NativeGitRepositoryFactory(configured);
        provider.resolveProvisional(
                NativeGitRepositoryFactory.CONFIGURATION_SOURCE,
                configuration.getBootstrap().getAccessControl(),
                configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing());
        return DaggerOrionComponent.builder()
                .bootstrapConfiguration(configuration)
                .rootPasswordReset(rootPasswordReset)
                .serverIdentityCapability(serverIdentity)
                .acmeKeyMaterialCapability(AcmeKeyMaterialCapability.unavailable())
                .configurationMaterialCapability(ConfigurationMaterialCapability.unavailable())
                .keyMaterialAdministrationCapability(KeyMaterialAdministrationCapability.unavailable())
                .initialConfiguration(java.util.Optional.empty())
                .configurationCipherCapability(ConfigurationCipherCapability.unavailable())
                .tlsCapability(TlsCapability.unavailable())
                .sshHostKeyCapability(SshHostKeyCapability.unavailable())
                .nativeGitRepositoryProvider(provider)
                .configuredRepositoryFactory(configured)
                .s3Transport(transport)
                .build();
    }

    private static NativeGitRepository repository(OrionComponent component) {
        return component.nativeGitRepositoryProvider().find(REPOSITORY_NAME)
                .valueOrFailure("internal configuration repository");
    }

    private static String publishCandidate(
            NativeGitRepository repository,
            String candidateName,
            byte[] content) throws Exception {
        String candidateId = saveCandidate(repository, candidateName, content);
        String expectedOldId = repository.refs().get(CONFIGURATION_REF);
        assertThat(publish(repository, expectedOldId, candidateId))
                .extracting(RefUpdateResult::status).containsExactly(RefUpdateResult.Status.APPLIED);
        return candidateId;
    }

    private static String saveCandidate(
            NativeGitRepository repository,
            String candidateName,
            byte[] content) throws Exception {
        String candidateRef = "refs/heads/candidate-" + candidateName;
        repository.files().withAccess(candidateRef, "candidate " + candidateName, GitCommitAuthor.EMPTY,
                fileAccess -> {
            fileAccess.write(ACL_PATH, content);
            fileAccess.apply();
            return null;
        });
        return repository.refs().get(candidateRef);
    }

    private static List<RefUpdateResult> publish(
            NativeGitRepository repository,
            String expectedOldId,
            String candidateId) {
        return repository.publishRefs(
                List.of(RefUpdate.fromWire(
                        CONFIGURATION_REF,
                        expectedOldId,
                        candidateId)),
                true);
    }

    private static byte[] aclBytes(String userId, String password) throws Exception {
        OrionPasswordHashingService hashingService = new OrionPasswordHashingService();
        String hash = hashingService.calculateHash(PasswordHashingAlgorithm.SHA1, password.toCharArray());
        User user = new User(userId, null, null, userId + "@example.test",
                List.of(new Credential(AccessControl.CredentialType.SHA1, hash)),
                List.of(), List.of());
        return accessControlBytes(new AccessControl(List.of(user), List.of(), List.of()));
    }

    private static byte[] missingRootAclBytes() throws Exception {
        OrionPasswordHashingService hashingService = new OrionPasswordHashingService();
        String hash = hashingService.calculateHash(
                PasswordHashingAlgorithm.SHA1,
                "alice-password".toCharArray());
        User alice = new User("alice", null, null, "alice@example.test",
                List.of(new Credential(AccessControl.CredentialType.SHA1, hash)),
                List.of("ALICE"), List.of());
        AccessControl acl = new AccessControl(List.of(alice), List.of(
                new Role("ALICE", List.of(), List.of("ALICE_READ")),
                new Role("ROOT", List.of(), List.of("APPLICATION_CONTROL"))), List.of(
                new Grant("ALICE_READ", List.of(
                        new GrantExpression(AccessControl.GrantKey.REPOSITORY, "alice/**"),
                        new GrantExpression(AccessControl.GrantKey.READ, "true"))),
                new Grant("CONNECT", List.of(
                        new GrantExpression(AccessControl.GrantKey.NETWORK_SOURCE, "192.0.2.1"))),
                new Grant("ALL_REPOSITORY", List.of(
                        new GrantExpression(AccessControl.GrantKey.REPOSITORY, "restricted"),
                        new GrantExpression(AccessControl.GrantKey.READ, "false"))),
                new Grant("APPLICATION_CONTROL", List.of(
                        new GrantExpression(AccessControl.GrantKey.ADMIN, "false")))));
        return accessControlBytes(acl);
    }

    private static byte[] noncanonicalRootAclBytes() throws Exception {
        User root = new User(
                "ROOT", null, null, "old-root@example.test",
                List.of(new Credential(AccessControl.CredentialType.SHA1, "old-hash")),
                List.of(), List.of());
        return accessControlBytes(new AccessControl(List.of(root), List.of(), List.of()));
    }

    private static byte[] defaultAclBytes(String password) throws Exception {
        OrionPasswordHashingService hashingService = new OrionPasswordHashingService();
        String hash = hashingService.calculateHash(
                PasswordHashingAlgorithm.SHA1,
                password.toCharArray());
        return accessControlBytes(DefaultAccessControl.create(
                hash,
                AccessControl.CredentialType.SHA1));
    }

    private static byte[] accessControlBytes(AccessControl accessControl) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(accessControl), output);
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
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(active.getPrivate());
            signature.update(payload);
            return signature.sign();
        }

        @Override
        public boolean hasVerificationKey(String keyId) {
            return "active".equals(keyId);
        }

        @Override
        public boolean verify(String keyId, byte[] payload, byte[] signatureBytes)
                throws GeneralSecurityException {
            if (!hasVerificationKey(keyId)) {
                return false;
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(active.getPublic());
            signature.update(payload);
            return signature.verify(signatureBytes);
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
        AccessControl accessControl = OrionXml.read(new ByteArrayInputStream(
                component.orionAccessControlService().accessControlConfigurationFile().content()))
                        .system().accessControl();
        User user = accessControl.users().stream()
                .filter(candidate -> userId.equals(candidate.id()))
                .findFirst()
                .orElseThrow();
        assertThat(user.credentials())
                .filteredOn(credential ->
                        credential.type() == AccessControl.CredentialType.OPENSSH_PUBLIC_KEY)
                .extracting(Credential::value)
                .containsExactlyInAnyOrder(expectedKeys);
    }

    private static String issueTokenForSshKey(
            OrionComponent component,
            String userId,
            KeyPair keyPair) {
        AuthenticationResult authentication = component.orionAccessControlService().authenticateSshUser(
                userId,
                keyPair.getPublic().getEncoded());
        assertThat(authentication).isInstanceOf(AuthenticationResult.Success.class);
        TokenRefreshResult issued = component.orionAccessControlService().refreshToken(
                (AuthenticationResult.Success) authentication,
                600);
        assertThat(issued).isInstanceOf(TokenRefreshResult.Success.class);
        return ((TokenRefreshResult.Success) issued).token();
    }

    private static void assertAuthenticated(OrionComponent component, String userId, String password) {
        assertThat(component.orionAccessControlService().authenticateUser(
                userId,
                password.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AuthenticationResult.Success.class);
    }

    private static void assertRecoveryPasswordOnly(OrionComponent component, String password) {
        byte[] credential = password.getBytes(StandardCharsets.UTF_8);
        assertThat(component.orionAccessControlService().authenticateUser("root", credential))
                .isInstanceOf(AuthenticationResult.Failure.class);
        assertThat(component.orionAccessControlService().authenticateSshKeyEnrollment("root", credential))
                .isInstanceOfSatisfying(SshKeyEnrollmentAuthentication.Success.class, success ->
                        assertThat(success.rootRecoveryGeneration()).isPresent());
    }
}
