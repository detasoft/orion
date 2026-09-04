package pro.deta.orion.acl;

import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.sshd.common.config.keys.PublicKeyEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionAccessControlServiceImplTest {
    private static final String ACL_PATH = "config/orion.xml";
    private static final String EXTRA_ACL_PATH = "config/team.xml";
    private static final KeyPair KEY_ONE = keyPair("RSA", 2048);
    private static final KeyPair KEY_TWO = keyPair("EC", 256);
    private static final KeyPair KEY_THREE = keyPair("RSA", 2048);

    @Test
    void listsCanonicalDeduplicatedSshCredentialsForOnlyTheSelectedUser() {
        AccessControlDraft primary = new AccessControlDraft();
        AccessControlDraft.User alice = user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic()))
                .addCredential(AccessControl.CredentialType.ARGON2, "password-hash")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()));
        primary.getUsers().add(alice);
        AccessControlDraft secondary = new AccessControlDraft();
        secondary.getUsers().add(user("bob")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_THREE.getPublic())));

        try (ServiceFixture fixture = fixture(primary, secondary)) {
            SshCredentialListResult result = fixture.service.listSshCredentials("ALICE");

            assertThat(result).isInstanceOf(SshCredentialListResult.Success.class);
            assertThat(((SshCredentialListResult.Success) result).credentials())
                    .containsExactlyInAnyOrder(descriptor(KEY_ONE.getPublic()), descriptor(KEY_TWO.getPublic()))
                    .isSortedAccordingTo(java.util.Comparator.comparing(SshCredential::fingerprint));
        }
    }

    @Test
    void reportsMalformedStoredSshCredentialsWithoutHidingThem() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "not-a-key"));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            assertFailure(
                    fixture.service.listSshCredentials("alice"),
                    SshCredentialFailureCode.INVALID_STORED_KEY);
        }
    }

    @Test
    void atomicallyAddsCanonicalKeysAndPreservesTheOtherAclFile() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "password-hash"));
        AccessControlDraft secondary = new AccessControlDraft();
        secondary.getUsers().add(user("bob")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_THREE.getPublic())));

        try (ServiceFixture fixture = fixture(primary, secondary)) {
            byte[] unchanged = fixture.storage.snapshot.files().get(EXTRA_ACL_PATH);
            String commented = key(KEY_ONE.getPublic()) + " alice@example";
            SshCredentialUpdateResult first = fixture.service.addSshCredentials(
                    "alice",
                    List.of(commented, key(KEY_TWO.getPublic()), commented));
            SshCredentialUpdateResult second = fixture.service.addSshCredentials("alice", List.of(commented));

            assertThat(first).isInstanceOfSatisfying(SshCredentialUpdateResult.Success.class, success -> {
                assertThat(success.changed()).isTrue();
                assertThat(success.credentials()).hasSize(2);
            });
            assertThat(second).isInstanceOfSatisfying(
                    SshCredentialUpdateResult.Success.class,
                    success -> assertThat(success.changed()).isFalse());
            assertThat(fixture.storage.snapshot.files().get(EXTRA_ACL_PATH)).isEqualTo(unchanged);
            assertThat(fixture.storage.saveCount).isEqualTo(1);
            assertThat(sshValues(fixture.storage.snapshot, ACL_PATH, "alice"))
                    .containsExactlyInAnyOrder(key(KEY_ONE.getPublic()), key(KEY_TWO.getPublic()));
        }
    }

    @Test
    void invalidAdditionAndMissingUserDoNotMutateTheSnapshot() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice"));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            assertFailure(
                    fixture.service.addSshCredentials("alice", List.of(key(KEY_ONE.getPublic()), "invalid")),
                    SshCredentialFailureCode.INVALID_KEY);
            assertFailure(
                    fixture.service.addSshCredentials("missing", List.of(key(KEY_ONE.getPublic()))),
                    SshCredentialFailureCode.USER_NOT_FOUND);
            assertThat(fixture.storage.saveCount).isZero();
        }
    }

    @Test
    void mapsAStaleConditionalSaveToConcurrentUpdateWithoutActivatingTheDraft() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice"));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            fixture.storage.concurrentOnSave = true;

            assertFailure(
                    fixture.service.addSshCredentials("alice", List.of(key(KEY_ONE.getPublic()))),
                    SshCredentialFailureCode.CONCURRENT_UPDATE);
            assertThat(sshValues(fixture.storage.snapshot, ACL_PATH, "alice")).isEmpty();
        }
    }

    @Test
    void addedRootKeysRetainTheExistingAuthenticationGeneration() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("root").addCredential(
                AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                "root-auth-generation:generation-one",
                key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            assertThat(fixture.service.addSshCredentials("root", List.of(key(KEY_TWO.getPublic()))))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);

            assertThat(credentials(fixture.storage.snapshot, ACL_PATH, "root"))
                    .filteredOn(credential -> credential.getType() == AccessControl.CredentialType.OPENSSH_PUBLIC_KEY)
                    .extracting(AccessControl.Credential::getKeyId)
                    .containsOnly("root-auth-generation:generation-one");
        }
    }

    @Test
    void removesEveryDuplicateOfAUniqueKeyWithoutTouchingOtherCredentialsOrUsers() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic()))
                .addCredential(AccessControl.CredentialType.ARGON2, "password-hash"));
        AccessControlDraft secondary = new AccessControlDraft();
        secondary.getUsers().add(user("bob")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary, secondary)) {
            byte[] secondaryBefore = fixture.storage.snapshot.files().get(EXTRA_ACL_PATH);
            SshCredentialUpdateResult removed = fixture.service.removeSshCredential(
                    "alice",
                    descriptor(KEY_ONE.getPublic()).fingerprint(),
                    false);

            assertThat(removed).isInstanceOfSatisfying(
                    SshCredentialUpdateResult.Success.class,
                    success -> assertThat(success.credentials()).containsExactly(descriptor(KEY_TWO.getPublic())));
            assertThat(credentials(fixture.storage.snapshot, ACL_PATH, "alice"))
                    .filteredOn(credential -> credential.getType() == AccessControl.CredentialType.ARGON2)
                    .singleElement()
                    .extracting(AccessControl.Credential::getValue)
                    .isEqualTo("password-hash");
            assertThat(fixture.storage.snapshot.files().get(EXTRA_ACL_PATH)).isEqualTo(secondaryBefore);
            assertThat(sshValues(fixture.storage.snapshot, EXTRA_ACL_PATH, "bob"))
                    .containsExactly(key(KEY_ONE.getPublic()));
        }
    }

    @Test
    void removalRejectsMissingAmbiguousMalformedAndUnforcedLastKeyWithoutSaving() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic())));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            String first = descriptor(KEY_ONE.getPublic()).fingerprint();
            String second = descriptor(KEY_TWO.getPublic()).fingerprint();
            assertFailure(
                    fixture.service.removeSshCredential("alice", commonPrefix(first, second), false),
                    SshCredentialFailureCode.AMBIGUOUS_MATCH);
            assertFailure(
                    fixture.service.removeSshCredential("alice", "SHA256:missing", false),
                    SshCredentialFailureCode.MISSING_MATCH);
            assertThat(fixture.storage.saveCount).isZero();

            assertThat(fixture.service.removeSshCredential("alice", first, false))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertFailure(
                    fixture.service.removeSshCredential("alice", second, false),
                    SshCredentialFailureCode.LAST_KEY_REQUIRES_FORCE);
            assertThat(fixture.storage.saveCount).isEqualTo(1);
        }

        AccessControlDraft malformed = new AccessControlDraft();
        malformed.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "not-a-key"));
        try (ServiceFixture fixture = fixture(malformed, new AccessControlDraft())) {
            assertFailure(
                    fixture.service.removeSshCredential("alice", "SHA256:any", true),
                    SshCredentialFailureCode.INVALID_STORED_KEY);
            assertThat(fixture.storage.saveCount).isZero();
        }
    }

    @Test
    void forcedNonRootRemovalCanRemoveTheLastKeyAndRepeatingItIsMissing() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            String fingerprint = descriptor(KEY_ONE.getPublic()).fingerprint();
            assertThat(fixture.service.removeSshCredential("alice", fingerprint, true))
                    .isInstanceOfSatisfying(
                            SshCredentialUpdateResult.Success.class,
                            success -> assertThat(success.credentials()).isEmpty());
            assertFailure(
                    fixture.service.removeSshCredential("alice", fingerprint, true),
                    SshCredentialFailureCode.MISSING_MATCH);
        }
    }

    @Test
    void rootRemovalPreservesGenerationUntilForcedLastKeyCreatesFailClosedState() {
        String generation = "generation-one";
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("RoOt")
                .addCredential(
                        AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                        "root-auth-generation:" + generation,
                        key(KEY_ONE.getPublic()))
                .addCredential(
                        AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                        "root-auth-generation:" + generation,
                        key(KEY_TWO.getPublic())));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            AuthenticationResult authentication = fixture.service.authenticateSshUser(
                    "root",
                    KEY_ONE.getPublic().getEncoded());
            assertThat(authentication).isInstanceOf(AuthenticationResult.Success.class);
            TokenIssueResult issued = fixture.service.issueTokenFor(
                    ((AuthenticationResult.Success) authentication).userIdentity(),
                    60);
            assertThat(issued).isInstanceOf(TokenIssueResult.Success.class);

            assertThat(fixture.service.removeSshCredential(
                    "root",
                    descriptor(KEY_ONE.getPublic()).fingerprint(),
                    false)).isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertThat(credentials(fixture.storage.snapshot, ACL_PATH, "root"))
                    .extracting(AccessControl.Credential::getKeyId)
                    .containsOnly("root-auth-generation:" + generation);

            assertThat(fixture.service.removeSshCredential(
                    "root",
                    descriptor(KEY_TWO.getPublic()).fingerprint(),
                    true)).isInstanceOf(SshCredentialUpdateResult.Success.class);

            assertFailure(
                    fixture.service.addSshCredentials("root", List.of(key(KEY_THREE.getPublic()))),
                    SshCredentialFailureCode.ROOT_LOCKED);
            assertThat(fixture.service.authenticateSshUser("root", KEY_TWO.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.authenticateUser("root", "anything".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            String token = ((TokenIssueResult.Success) issued).token();
            assertThat(fixture.service.authenticateToken(token.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.issueTokenFor(
                    ((AuthenticationResult.Success) authentication).userIdentity(),
                    60)).isInstanceOf(TokenIssueResult.Failure.class);
            assertThat(sshValues(fixture.storage.snapshot, ACL_PATH, "root")).isEmpty();
            assertThat(credentials(fixture.storage.snapshot, ACL_PATH, "root"))
                    .singleElement()
                    .extracting(AccessControl.Credential::getKeyId)
                    .asString()
                    .startsWith("root-auth-locked:");
        }
    }

    @Test
    void lockedRootIsSkippedByEveryPublicKeyResolver() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("root")
                .addCredential(
                        AccessControl.CredentialType.ARGON2,
                        "root-auth-locked:generation",
                        "locked-hash")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary, new AccessControlDraft())) {
            assertThat(fixture.service.authenticateSshUser("root", KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.authenticateGitSshKey(KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOfSatisfying(
                            AuthenticationResult.Success.class,
                            success -> assertThat(success.userIdentity().getUserId()).isEqualTo("alice"));
        }
    }

    @Test
    void adminUserUpdateMutatesOnlyTheUsersOwningAclFile() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        AccessControlDraft secondary = new AccessControlDraft();
        secondary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "old-hash"));

        try (ServiceFixture fixture = fixture(primary, secondary)) {
            byte[] primaryBefore = fixture.storage.snapshot.files().get(ACL_PATH);

            fixture.service.createOrUpdateUser(userUpdate("alice", "new-hash"));

            assertThat(fixture.storage.snapshot.files()).containsOnlyKeys(ACL_PATH, EXTRA_ACL_PATH);
            assertThat(fixture.storage.snapshot.files().get(ACL_PATH)).isEqualTo(primaryBefore);
            assertThat(credentials(fixture.storage.snapshot, EXTRA_ACL_PATH, "alice"))
                    .singleElement()
                    .extracting(AccessControl.Credential::getValue)
                    .isEqualTo("new-hash");
        }
    }

    @Test
    void adminUpdateWaitsForCredentialMutationAndCannotResurrectRootKey() throws Exception {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        AccessControlDraft secondary = new AccessControlDraft();
        secondary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "old-hash"));

        try (ServiceFixture fixture = fixture(primary, secondary);
             var executor = Executors.newFixedThreadPool(2)) {
            fixture.storage.blockCredentialRemoval = true;
            var removal = executor.submit(() -> fixture.service.removeSshCredential(
                    "root",
                    descriptor(KEY_ONE.getPublic()).fingerprint(),
                    true));
            assertThat(fixture.storage.credentialRemovalSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch adminStarted = new CountDownLatch(1);
            var adminUpdate = executor.submit(() -> {
                adminStarted.countDown();
                fixture.service.createOrUpdateUser(userUpdate("alice", "new-hash"));
            });
            assertThat(adminStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(adminUpdate.isDone()).isFalse();

            fixture.storage.continueCredentialRemoval.countDown();

            assertThat(removal.get()).isInstanceOf(SshCredentialUpdateResult.Success.class);
            adminUpdate.get();
            assertThat(sshValues(fixture.storage.snapshot, ACL_PATH, "root")).isEmpty();
            assertThat(credentials(fixture.storage.snapshot, ACL_PATH, "root"))
                    .extracting(AccessControl.Credential::getKeyId)
                    .singleElement()
                    .asString()
                    .startsWith("root-auth-locked:");
            assertThat(credentials(fixture.storage.snapshot, EXTRA_ACL_PATH, "alice"))
                    .singleElement()
                    .extracting(AccessControl.Credential::getValue)
                    .isEqualTo("new-hash");
        }
    }

    @Test
    void internalServerKeySynchronizationMutatesOnlyTheRootOwningFile() {
        AccessControlDraft primary = new AccessControlDraft();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "alice-hash"));
        byte[] primaryBefore = serialize(primary.toAccessControl());
        AccessControlDraft secondary = new AccessControlDraft();
        secondary.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(
                primary,
                secondary,
                testServerIdentity(List.of(KEY_THREE.getPublic())))) {
            assertThat(fixture.storage.snapshot.files()).containsOnlyKeys(ACL_PATH, EXTRA_ACL_PATH);
            assertThat(fixture.storage.snapshot.files().get(ACL_PATH)).isEqualTo(primaryBefore);
            assertThat(fixture.service.listSshCredentials("root"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Success.class, success ->
                            assertThat(success.credentials()).containsExactlyInAnyOrder(
                                    descriptor(KEY_ONE.getPublic()),
                                    descriptor(KEY_THREE.getPublic())));
        }
    }

    @Test
    void resetFailsWithoutPrintingWhenThePersistedAclCannotBeReloaded() throws Exception {
        assertRecoveryFailsWithoutPrinting(defaultAclSnapshot(), new OrionRuntimeOptions(true));
    }

    @Test
    void defaultCreationFailsWithoutPrintingWhenThePersistedAclCannotBeReloaded() throws Exception {
        assertRecoveryFailsWithoutPrinting(null, OrionRuntimeOptions.defaults());
    }

    private static void assertRecoveryFailsWithoutPrinting(
            AccessControlSnapshot initial,
            OrionRuntimeOptions runtimeOptions) {
        FailingReloadStorage storage = new FailingReloadStorage(initial);
        OrionEventManager eventManager = new OrionEventManager();
        OrionProvider provider = new OrionProvider(() -> null, () -> eventManager, () -> null);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(
                storage,
                new OrionPasswordHashingService(),
                provider,
                new OrionConfiguration(),
                runtimeOptions,
                testServerIdentity());
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        eventManager.onStart();
        try {
            System.setOut(new PrintStream(processOutput, true, StandardCharsets.UTF_8));

            assertThatThrownBy(service::onStart)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Configuration repository not initialized");
        } finally {
            System.setOut(originalOut);
            service.onStop();
            eventManager.onStop();
        }

        assertThat(storage.saved).isTrue();
        assertThat(processOutput.toString(StandardCharsets.UTF_8)).doesNotContain("---ROOT PASSWORD: ");
    }

    private static AccessControlSnapshot defaultAclSnapshot() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XmlService().serialize(ACLUtil.generateDefaultAccessControl("old-password-hash"), output);
        return new AccessControlSnapshot(Map.of(ACL_PATH, output.toByteArray()), Optional.of("initial"));
    }

    private static ServiceFixture fixture(AccessControlDraft primary, AccessControlDraft secondary) {
        return fixture(primary, secondary, testServerIdentity());
    }

    private static ServiceFixture fixture(
            AccessControlDraft primary,
            AccessControlDraft secondary,
            ServerIdentityCapability serverIdentity) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(ACL_PATH, serialize(primary.toAccessControl()));
        files.put(EXTRA_ACL_PATH, serialize(secondary.toAccessControl()));
        InMemoryStorage storage = new InMemoryStorage(
                new AccessControlSnapshot(files, Optional.of("version-one")));
        OrionEventManager eventManager = new OrionEventManager();
        OrionProvider provider = new OrionProvider(() -> null, () -> eventManager, () -> null);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(
                storage,
                new OrionPasswordHashingService(),
                provider,
                new OrionConfiguration(),
                OrionRuntimeOptions.defaults(),
                serverIdentity);
        eventManager.onStart();
        service.onStart();
        return new ServiceFixture(service, storage, eventManager);
    }

    private static AccessControlDraft.User user(String id) {
        AccessControlDraft.User user = new AccessControlDraft.User();
        user.setId(id);
        user.setEmail(id + "@example.test");
        return user;
    }

    private static AccessControlUserUpdate userUpdate(String id, String passwordHash) {
        return new AccessControlUserUpdate(
                id,
                id + "@updated.example.test",
                List.of(new AccessControlCredentialUpdate(AccessControl.CredentialType.ARGON2, passwordHash)),
                List.of());
    }

    private static byte[] serialize(AccessControl accessControl) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new XmlService().serialize(accessControl, output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AccessControl parse(byte[] content) {
        try {
            return new XmlService().deserialize(new java.io.ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> sshValues(AccessControlSnapshot snapshot, String path, String userId) {
        for (AccessControl.User user : parse(snapshot.files().get(path)).getUsers()) {
            if (userId.equalsIgnoreCase(user.getId())) {
                return user.getCredentials().stream()
                        .filter(credential -> credential.getType() == AccessControl.CredentialType.OPENSSH_PUBLIC_KEY)
                        .map(AccessControl.Credential::getValue)
                        .toList();
            }
        }
        return List.of();
    }

    private static List<AccessControl.Credential> credentials(
            AccessControlSnapshot snapshot,
            String path,
            String userId) {
        for (AccessControl.User user : parse(snapshot.files().get(path)).getUsers()) {
            if (userId.equalsIgnoreCase(user.getId())) {
                return user.getCredentials();
            }
        }
        return List.of();
    }

    private static String key(PublicKey publicKey) {
        return PublicKeyEntry.toString(publicKey);
    }

    private static SshCredential descriptor(PublicKey publicKey) {
        return new SshCredential(
                org.apache.sshd.common.config.keys.KeyUtils.getKeyType(publicKey),
                org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(publicKey));
    }

    private static KeyPair keyPair(String algorithm, int size) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            if (size > 0) {
                generator.initialize(size);
            }
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static ServerIdentityCapability testServerIdentity() {
        return testServerIdentity(List.of());
    }

    private static ServerIdentityCapability testServerIdentity(List<PublicKey> publicKeys) {
        return new ServerIdentityCapability() {
            @Override
            public String activeKeyId() {
                return "test-signing-key";
            }

            @Override
            public byte[] sign(byte[] payload) throws java.security.GeneralSecurityException {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(KEY_THREE.getPrivate());
                signature.update(payload);
                return signature.sign();
            }

            @Override
            public boolean hasVerificationKey(String keyId) {
                return activeKeyId().equals(keyId);
            }

            @Override
            public boolean verify(String keyId, byte[] payload, byte[] signatureBytes)
                    throws java.security.GeneralSecurityException {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initVerify(KEY_THREE.getPublic());
                signature.update(payload);
                return signature.verify(signatureBytes);
            }

            @Override
            public List<PublicKey> publicKeys() {
                return publicKeys;
            }

            @Override
            public List<PublicKey> retainedPublicKeys() {
                return List.of();
            }
        };
    }

    private static String commonPrefix(String first, String second) {
        int length = Math.min(first.length(), second.length());
        int index = 0;
        while (index < length && first.charAt(index) == second.charAt(index)) {
            index++;
        }
        return first.substring(0, index);
    }

    private static void assertFailure(SshCredentialListResult result, SshCredentialFailureCode code) {
        assertThat(result).isInstanceOfSatisfying(
                SshCredentialListResult.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static void assertFailure(SshCredentialUpdateResult result, SshCredentialFailureCode code) {
        assertThat(result).isInstanceOfSatisfying(
                SshCredentialUpdateResult.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private record ServiceFixture(
            OrionAccessControlServiceImpl service,
            InMemoryStorage storage,
            OrionEventManager eventManager) implements AutoCloseable {
        @Override
        public void close() {
            service.onStop();
            eventManager.onStop();
        }
    }

    private static final class InMemoryStorage implements AccessControlStorage {
        private volatile AccessControlSnapshot snapshot;
        private int saveCount;
        private boolean concurrentOnSave;
        private boolean blockCredentialRemoval;
        private final CountDownLatch credentialRemovalSaveEntered = new CountDownLatch(1);
        private final CountDownLatch continueCredentialRemoval = new CountDownLatch(1);

        private InMemoryStorage(AccessControlSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Result<AccessControlSnapshot> load() {
            return new Result.Success<>(snapshot);
        }

        @Override
        public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
            if (blockCredentialRemoval && request.message().startsWith("remove SSH credential")) {
                credentialRemovalSaveEntered.countDown();
                try {
                    continueCredentialRemoval.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while blocking credential removal", error);
                }
            }
            if (concurrentOnSave) {
                throw new AccessControlConcurrentUpdateException("simulated race", null);
            }
            if (!this.snapshot.version().equals(snapshot.version())) {
                throw new IllegalStateException("version conflict");
            }
            this.snapshot = snapshot;
            saveCount++;
        }

        @Override
        public String primaryPath() {
            return ACL_PATH;
        }
    }

    private static final class FailingReloadStorage implements AccessControlStorage {
        private final AccessControlSnapshot initial;
        private boolean saved;

        private FailingReloadStorage(AccessControlSnapshot initial) {
            this.initial = initial;
        }

        @Override
        public Result<AccessControlSnapshot> load() {
            if (saved) {
                return new Result.Failure<>(
                        Result.FailureCode.GENERAL,
                        "simulated reload failure",
                        new IOException("simulated reload failure"));
            }
            if (initial == null) {
                return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
            }
            return new Result.Success<>(initial);
        }

        @Override
        public void save(AccessControlSnapshot snapshot, AccessControlSaveRequest request) {
            saved = true;
        }

        @Override
        public String primaryPath() {
            return ACL_PATH;
        }
    }
}
