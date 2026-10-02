package pro.deta.orion.acl;

import pro.deta.orion.config.OrionConfigurationEdit;

import pro.deta.orion.config.OrionConfigurationEditor;

import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.v2.*;
import pro.deta.orion.schema.orion.v2.RoleId;
import pro.deta.orion.schema.orion.v2.ScopedGrant;
import pro.deta.orion.schema.orion.v2.ScopedRole;
import pro.deta.orion.schema.orion.v2.TeamId;
import pro.deta.orion.schema.orion.v2.RepositoryId;
import pro.deta.orion.schema.orion.v2.RepositoryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.config.ConfigurationFile;
import pro.deta.orion.config.OrionConfigurationStorage;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AccessControlValidationException;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.ConfigurationSecretContext;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelope;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelopeCodec;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.util.Result;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.config.keys.PublicKeyEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionAccessControlServiceImplTest {
    private static final KeyPair KEY_ONE = keyPair("RSA", 2048);
    private static final KeyPair KEY_TWO = keyPair("EC", 256);
    private static final KeyPair KEY_THREE = keyPair("RSA", 2048);

    @Test
    void stagesTwoSshKeysAndPublishesOnlyOnce() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("alice"));
        try (ServiceFixture fixture = fixture(initial);
                OrionConfigurationEdit edit = fixture.editor.edit()) {
            assertThat(fixture.service.addSshCredentials(edit, "alice", List.of(key(KEY_ONE.getPublic()))))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertThat(fixture.service.addSshCredentials(edit, "alice", List.of(key(KEY_THREE.getPublic()))))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertThat(fixture.storage.saveCount).isZero();
            assertThat(fixture.service.authenticateSshUser("alice", KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            edit.apply("add two SSH credentials", new UserEmail("alice", "alice@example.test"));
            assertThat(fixture.storage.saveCount).isEqualTo(1);
            assertThat(fixture.service.authenticateSshUser("alice", KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
            assertThat(fixture.service.authenticateSshUser("alice", KEY_THREE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
        }
    }

    @Test
    void discardedAndConflictingEditsPublishNothing() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("alice"));
        try (ServiceFixture fixture = fixture(initial)) {
            try (OrionConfigurationEdit edit = fixture.editor.edit()) {
                fixture.service.addSshCredentials(edit, "alice", List.of(key(KEY_ONE.getPublic())));
            }
            assertThat(fixture.storage.saveCount).isZero();
            try (OrionConfigurationEdit edit = fixture.editor.edit()) {
                fixture.service.addSshCredentials(edit, "alice", List.of(key(KEY_TWO.getPublic())));
                fixture.storage.concurrentOnSave = true;
                assertThatThrownBy(() -> edit.apply("conflicting edit", UserEmail.EMPTY))
                        .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            }
            assertThat(fixture.storage.saveCount).isZero();
            assertThat(fixture.service.authenticateSshUser("alice", KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.authenticateSshUser("alice", KEY_TWO.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
        }
    }

    @Test
    void nonAclEditPublishesWithoutAnAccessControlService() {
        InMemoryStorage storage = new InMemoryStorage(new ConfigurationFile(serialize(new AccessControl()), Optional.empty()));
        OrionDesiredState desired = new OrionDesiredState();
        OrionConfigurationEditor editor = new OrionConfigurationEditor(
                storage, new OrionConfiguration(), testCipher(), testMaterial(), desired);
        try (OrionConfigurationEdit edit = editor.edit()) {
            edit.update(document -> new OrionDocument(new OrionDocument.SystemConfiguration(
                    document.system().accessControl(), document.system().https(),
                    List.of(new ConfigurationSecret("credential", testEnvelope())),
                    document.system().proxies(), document.system().connections()), document.organizations()));
            assertThat(desired.isPublished()).isFalse();
            OrionDesiredState.Snapshot saved = edit.apply("configure secret", UserEmail.EMPTY);
            assertThat(desired.current()).isSameAs(saved);
            assertThat(saved.document().system().secrets()).extracting(ConfigurationSecret::id)
                    .containsExactly("credential");
        }
    }

    @Test
    void externalReloadPreparesRootServerKeysBeforePublication() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        try (ServiceFixture fixture = fixture(initial,
                testServerIdentity(List.of(KEY_THREE.getPublic())))) {
            fixture.storage.snapshot = new ConfigurationFile(
                    serialize(initial.toAccessControl()), fixture.storage.snapshot.revision());
            int saves = fixture.storage.saveCount;
            fixture.storage.changeListener.accept("external root update");
            assertThat(fixture.storage.saveCount).isEqualTo(saves + 1);
            assertThat(fixture.service.authenticateSshUser("root", KEY_THREE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
            assertThat(sshValues(fixture.storage.snapshot, "root"))
                    .extracting(value -> descriptor(pro.deta.orion.util.KeyUtils.readPublicKeyFromString(value)))
                    .contains(descriptor(KEY_THREE.getPublic()));
        }
    }

    @Test
    void failedStartupDoesNotRetainPreparationOnRetry() {
        InMemoryStorage storage = new InMemoryStorage(new ConfigurationFile(serialize(new AccessControl()), Optional.empty()));
        OrionDesiredState configurationState = new OrionDesiredState();
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage,
                new OrionConfiguration(),
                testCipher(),
                testMaterial(),
                configurationState);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                testServerIdentity(List.of(KEY_THREE.getPublic())),
                configurationState,
                editor,
                Optional.empty());
        storage.loadUnavailable = true;
        assertThatThrownBy(service::onStart).isInstanceOf(IllegalStateException.class);
        storage.loadUnavailable = false;
        service.onStart();
        try (OrionConfigurationEdit edit = editor.edit()) {
            service.createOrUpdateUser(edit, userUpdate("alice", "hash"));
            edit.apply("create alice", UserEmail.EMPTY);
            assertThat(service.userExists("alice")).isTrue();
        } finally {
            service.onStop();
        }
        AclFixture root = new AclFixture();
        root.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        storage.snapshot = new ConfigurationFile(serialize(root.toAccessControl()), storage.snapshot.revision());
        int saves = storage.saveCount;
        editor.reload("after stopped startup retry");
        assertThat(storage.saveCount).isEqualTo(saves);
        assertThat(service.authenticateSshUser("root", KEY_THREE.getPublic().getEncoded()))
                .isInstanceOf(AuthenticationResult.Failure.class);
    }

    @Test
    void reloadFailureAfterSaveConsumesTheEditWithoutClaimingRollback() {
        FailingReloadStorage storage = new FailingReloadStorage(
                new ConfigurationFile(serialize(new AccessControl()), Optional.empty()));
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage,
                new OrionConfiguration(),
                testCipher(),
                testMaterial(),
                new pro.deta.orion.config.OrionDesiredState());
        try (OrionConfigurationEdit edit = editor.edit()) {
            assertThatThrownBy(() -> edit.apply("saved before reload failure", UserEmail.EMPTY))
                    .isInstanceOf(OrionConfigurationEditor.ActivationFailedException.class);
            assertThat(storage.saved).isTrue();
            assertThatThrownBy(() -> edit.apply("retry", UserEmail.EMPTY))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Configuration edit is closed");
        }
    }

    @Test
    void createsAUserInTheConfigurationFile() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice"));

        try (ServiceFixture fixture = fixture(primary)) {
            assertThat(fixture.service.listSshCredentials("bob"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(SshCredentialFailureCode.USER_NOT_FOUND));
            fixture.createOrUpdateUser(userUpdate("bob", "new-password-hash"));
            assertThat(parse(fixture.storage.snapshot.content()).getUsers())
                    .extracting(AccessControl.User::getId).containsExactlyInAnyOrder("alice", "bob");
        }
    }

    @Test
    void connectionSelectorNeverGrantsAdministrationInSystemOrOrganizationScope() {
        AccessControl.Grant mixed = new AccessControl.Grant("mixed", List.of(
                new AccessControl.GrantExpression(AccessControl.GrantKey.CONNECTION, "*"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.ADMIN, "true")));
        AccessControl.User actor = new AccessControl.User("alice", "", "", "", List.of(), List.of(), List.of(mixed));
        OrionDocument.Organization org = new OrionDocument.Organization(new OrganizationId("acme"), "",
                List.of(actor), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(
                new AccessControl(List.of(actor), List.of(), List.of())), List.of(org));
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            assertThat(fixture.service.canAdminister(PrincipalAddress.parse("system/alice"),
                    Optional.empty(), document)).isFalse();
            assertThat(fixture.service.canAdminister(PrincipalAddress.parse("acme/alice"),
                    Optional.of(ConfigurationScope.parse("acme")), document)).isFalse();
        }
    }

    @Test
    void userMutationsArePersistedAndActiveWhenTheyReturn() throws Exception {
        OrionPasswordHashingService hashing = new OrionPasswordHashingService();
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            fixture.createOrUpdateUser(userUpdate("alice",
                    hashing.calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.ARGON2,
                            "first-password".toCharArray())));
            assertThat(fixture.service.authenticateUser("alice", "first-password".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Success.class);
            fixture.createOrUpdateUser(userUpdate("alice",
                    hashing.calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.ARGON2,
                            "second-password".toCharArray())));
            assertThat(fixture.service.authenticateUser("alice", "first-password".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.authenticateUser("alice", "second-password".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Success.class);
            assertThat(parse(fixture.storage.snapshot.content()).getUsers())
                    .extracting(AccessControl.User::getId).containsExactly("alice");
        }
    }

    @Test
    void configurationStatusTracksStoredValidationSeparatelyFromActiveRevision() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            OrionAccessControlServiceImpl.ConfigurationStatus initial = fixture.service.configurationStatus();
            assertThat(initial.storedRevision()).contains("version-one");
            assertThat(initial.activeRevision()).contains("version-one");
            assertThat(initial.validation()).isEqualTo("valid");

            fixture.storage.snapshot = new ConfigurationFile(
                    "<invalid".getBytes(StandardCharsets.UTF_8), Optional.of("version-two"));
            OrionAccessControlServiceImpl.ConfigurationStatus invalid = fixture.service.configurationStatus();
            assertThat(invalid.storedRevision()).contains("version-two");
            assertThat(invalid.activeRevision()).contains("version-one");
            assertThat(invalid.validation()).isEqualTo("invalid");

            fixture.storage.loadUnavailable = true;
            OrionAccessControlServiceImpl.ConfigurationStatus unavailable = fixture.service.configurationStatus();
            assertThat(unavailable.storedRevision()).isEmpty();
            assertThat(unavailable.activeRevision()).contains("version-one");
            assertThat(unavailable.validation()).isEqualTo("unavailable");
            fixture.storage.loadUnavailable = false;

            fixture.storage.snapshot = new ConfigurationFile(serialize(new AccessControl()), Optional.of("version-three"));
            fixture.editor.reload("test recovery");
            OrionAccessControlServiceImpl.ConfigurationStatus recovered = fixture.service.configurationStatus();
            assertThat(recovered.storedRevision()).contains("version-three");
            assertThat(recovered.activeRevision()).contains("version-three");
            assertThat(recovered.validation()).isEqualTo("valid");
        }
    }

    @Test
    void organizationIdentityCannotIssueTokenAsSystemUserWithSameId() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice"));
        try (ServiceFixture fixture = fixture(primary)) {
            InternalUserImpl identity = new InternalUserImpl("alice", new OrganizationId("acme"),
                    () -> OrionDocument.withAccessControl(new AccessControl()));
            assertThat(fixture.service.refreshToken(new AuthenticationResult.Success(identity), 60))
                    .isInstanceOf(TokenRefreshResult.Failure.class);
        }
    }

    @Test
    void verifiedOrganizationTokenUsesCurrentScopedRolesAndUserPresence() {
        OrionDesiredState desired = new OrionDesiredState();
        OrganizationId organization = new OrganizationId("acme");
        String issuer = "https://login.example.test";
        AccessControl.User assigned = new AccessControl.User("alice", null, null, null,
                List.of(new AccessControl.Credential(AccessControl.CredentialType.OIDC_SUBJECT, issuer, "alice")),
                List.of("acme/reader"), List.of());
        desired.publish(organizationTokenDocument(List.of(assigned)), Optional.empty());
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(null,
                null,
                null,
                testServerIdentity(),
                desired,
                new OrionConfigurationEditor(null, new OrionConfiguration(), testCipher(), testMaterial(), desired),
                Optional.empty());
        TokenIssueResult issued = service.issueOrganizationToken(organization, "alice", issuer, "alice", 60);
        assertThat(issued).isInstanceOf(TokenIssueResult.Success.class);
        byte[] token = ((TokenIssueResult.Success) issued).token().getBytes(StandardCharsets.UTF_8);
        TokenAuthenticationResult authenticated = service.verifyToken(token);
        assertThat(authenticated).isInstanceOf(TokenAuthenticationResult.Success.class);
        SecurityContext context = SecurityContext.createContext().withUserIdentity(
                ((TokenAuthenticationResult.Success) authenticated).userIdentity());
        RepositoryResource repository = RepositoryResource.of("acme/team/repo");
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isTrue();
        AccessControl.User revoked = new AccessControl.User("alice", null, null, null,
                assigned.getCredentials(), List.of(), List.of());
        desired.publish(organizationTokenDocument(List.of(revoked)), Optional.empty());
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isFalse();
        desired.publish(organizationTokenDocument(List.of(assigned)), Optional.empty());
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isTrue();
        desired.publish(organizationTokenDocument(List.of()), Optional.empty());
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isFalse();
        assertThat(service.verifyToken(token)).isInstanceOf(TokenAuthenticationResult.Failure.class);
    }

    private static OrionDocument organizationTokenDocument(List<AccessControl.User> users) {
        ScopedGrant grant = new ScopedGrant(new GrantId("read"), ScopedGrant.Effect.ALLOW,
                List.of(new AccessControl.GrantExpression(AccessControl.GrantKey.READ, "true")));
        ScopedRole role = new ScopedRole(new RoleId("reader"), List.of(),
                List.of(GrantAddress.parse("acme/read")));
        OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("repo"), "",
                OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), List.of(), java.util.Optional.empty());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                List.of(repository));
        OidcProvider provider = new OidcProvider("oidc", URI.create("https://login.example.test"), "client",
                "secret", OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0);
        OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("acme"), "",
                users, List.of(grant), List.of(role), List.of(team),
                List.of(new ConfigurationSecret("secret", "opaque-test-envelope")), List.of(provider), List.of(), List.of());
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
    }

    @Test
    void authenticationKeepsRoleGrantsFromItsUserSnapshotDuringReload() {
        authenticationKeepsGrantsDuringReload(false);
    }

    @Test
    void authenticationKeepsReferencedGrantsFromItsUserSnapshotDuringReload() {
        authenticationKeepsGrantsDuringReload(true);
    }

    private void authenticationKeepsGrantsDuringReload(boolean referenced) {
        AtomicReference<Runnable> duringPasswordCheck = new AtomicReference<>();
        OrionPasswordHashingService hashing = new OrionPasswordHashingService() {
            @Override
            public boolean comparePassword(PasswordHashingAlgorithm algorithm, String expected, byte[] provided) {
                Runnable reload = duringPasswordCheck.getAndSet(null);
                if (reload != null) {
                    reload.run();
                }
                return super.comparePassword(algorithm, expected, provided);
            }
        };
        AclFixture primary = new AclFixture();
        AclFixture.User alice = user("alice");
        String passwordHash = hashing.calculateHash(PasswordHashingAlgorithm.SHA1, "password".toCharArray());
        alice.addCredential(AccessControl.CredentialType.SHA1, passwordHash);
        alice.addRole("operators");
        primary.getUsers().add(alice);
        AclFixture.User bob = user("bob");
        bob.addCredential(AccessControl.CredentialType.SHA1, passwordHash);
        primary.getUsers().add(bob);
        AclFixture.Role role = new AclFixture.Role();
        role.setId("operators");
        AclFixture.Grant grant = new AclFixture.Grant();
        grant.setId("operator-rights");
        grant.addKey(AccessControl.GrantKey.READ, "team/*");
        AccessControl.Grant originalGrant = grant.toAccessControl();
        if (referenced) {
            role.addGrantReference(grant.getId());
            primary.getGrants().add(grant);
        } else {
            role.addGrant(grant);
        }
        primary.getRoles().add(role);
        byte[] password = "password".getBytes(StandardCharsets.UTF_8);
        try (ServiceFixture fixture = fixture(primary, testServerIdentity(), hashing)) {
            AuthenticationResult.Success before = (AuthenticationResult.Success)
                    fixture.service.authenticateUser("alice", password);
            assertThat(before.userIdentity().getGrants()).containsExactly(originalGrant);
            alice.getRoles().clear();
            bob.addRole("operators");
            grant.getInfo().clear();
            grant.addKey(AccessControl.GrantKey.ADMIN, "true");
            duringPasswordCheck.set(() -> {
                fixture.storage.snapshot = new ConfigurationFile(
                        serialize(primary.toAccessControl()), Optional.of("version-two"));
                fixture.storage.changeListener.accept("replace operators membership");
            });

            AuthenticationResult.Success overlapping = (AuthenticationResult.Success)
                    fixture.service.authenticateUser("alice", password);
            assertThat(overlapping.userIdentity().getGrants()).containsExactly(originalGrant);
            AuthenticationResult.Success aliceAfter = (AuthenticationResult.Success)
                    fixture.service.authenticateUser("alice", password);
            assertThat(aliceAfter.userIdentity().getGrants()).isEmpty();
            AuthenticationResult.Success bobAfter = (AuthenticationResult.Success)
                    fixture.service.authenticateUser("bob", password);
            assertThat(bobAfter.userIdentity().getGrants()).containsExactly(grant.toAccessControl());
        }
    }

    @Test
    void updatesConfigurationAtTheReadRevisionInOneFile() throws Exception {
        try (var fixture = fixture(new AclFixture())) {
            var result = fixture.editor.edit("version-one").update(document ->
                    new OrionDocument(new OrionDocument.SystemConfiguration(document.system().accessControl(),
                            document.system().https(), List.of(new ConfigurationSecret(
                            "credential", testEnvelope())), document.system().proxies(),
                                    document.system().connections()), document.organizations())).apply("update proxy", null);

            assertThat(result.document().system().secrets()).extracting("id").containsExactly("credential");
            assertThat(fixture.storage.snapshot.revision()).contains("version-one");
            assertThat(parseDocument(fixture.storage.snapshot.content()).system().secrets())
                    .isEqualTo(result.document().system().secrets());
        }
    }

    @Test
    void rejectsStalePrimaryConfigurationBeforeInvokingTheMutation() {
        try (var fixture = fixture(new AclFixture())) {
            int saves = fixture.storage.saveCount;
            assertThatThrownBy(() -> fixture.editor.edit("stale").update(document -> {
                throw new AssertionError("A stale mutation must not consume credentials");
            }).apply("update proxy", null))
                    .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            assertThat(fixture.storage.saveCount).isEqualTo(saves);
        }
    }

    @Test
    void reportsAStaleRevisionEvenWhenTheNewHeadIsInvalid() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            fixture.storage.snapshot = new ConfigurationFile("<invalid".getBytes(StandardCharsets.UTF_8), Optional.of("version-two"));
            assertThatThrownBy(() -> fixture.editor.edit("version-one").update(document -> {
                throw new AssertionError("A stale mutation must not parse or change the new head");
            }).apply("update ACL", null))
                    .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            assertThat(fixture.storage.saveCount).isZero();
        }
    }

    @Test
    void publishesAndPreservesTheWholeDesiredStateWhenAclChanges() throws Exception {
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                true,
                "localhost",
                8443,
                URI.create("https://localhost:8443"),
                Optional.of(new OrionMaterialReference("https-identity", 1)),
                Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                List.of(),
                Optional.empty());
        OrionDocument initial = new OrionDocument(
                new OrionDocument.SystemConfiguration(
                        new AccessControl(),
                        Optional.of(https),
                        List.of(), List.of(), List.of()),
                List.of());
        InMemoryStorage storage = new InMemoryStorage(new ConfigurationFile(
                serialize(initial), Optional.of("version-one")));
        OrionDesiredState desiredState = new OrionDesiredState();
        OrionConfigurationEditor editor =
                new OrionConfigurationEditor(storage,
                new OrionConfiguration(),
                testCipher(),
                testMaterial(),
                desiredState);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                testServerIdentity(),
                desiredState,
                editor,
                Optional.empty());
        service.onStart();
        try {
            assertThat(desiredState.current().revision()).contains("version-one");
            assertThat(desiredState.current().document().system().https()).contains(https);

            try (OrionConfigurationEdit edit = editor.edit()) {
                service.createOrUpdateUser(edit, userUpdate("alice", "password-hash"));
                edit.apply("update alice", UserEmail.EMPTY);
            }

            OrionDocument persisted = parseDocument(storage.snapshot.content());
            assertThat(persisted.system().https()).contains(https);
            assertThat(desiredState.current().document()).isEqualTo(persisted);

            OrionDesiredState.Snapshot lastValid = desiredState.current();
            storage.snapshot = new ConfigurationFile(
                    "not xml".getBytes(StandardCharsets.UTF_8), Optional.of("broken-version"));
            storage.changeListener.accept("malformed desired state");

            assertThat(desiredState.current()).isSameAs(lastValid);
        } finally {
            service.onStop();
        }
    }

    @Test
    void missingHttpsMaterialRetainsTheLastPublishedDocumentAndAcl() throws Exception {
        AccessControl initialAcl = new AccessControl();
        OrionDocument initial = OrionDocument.withAccessControl(initialAcl);
        InMemoryStorage storage = new InMemoryStorage(new ConfigurationFile(
                serialize(initial), Optional.of("valid-commit")));
        OrionDesiredState desiredState = new OrionDesiredState();
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                testServerIdentity(),
                desiredState,
                new OrionConfigurationEditor(storage, new OrionConfiguration(), testCipher(),
                        ConfigurationMaterialCapability.unavailable(), desiredState),
                Optional.empty());
        service.onStart();
        try {
            OrionDesiredState.Snapshot lastValid = desiredState.current();
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(true, "localhost", 8443,
                    URI.create("https://localhost:8443"),
                    Optional.of(new OrionMaterialReference("missing-identity", 1)), Optional.empty(),
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.empty());
            OrionDocument candidate = new OrionDocument(new OrionDocument.SystemConfiguration(
                    initialAcl, Optional.of(https), List.of(), List.of(), List.of()), List.of());
            storage.snapshot = new ConfigurationFile(serialize(candidate),
                    Optional.of("invalid-commit"));

            storage.changeListener.accept("missing material");

            assertThat(desiredState.current()).isSameAs(lastValid);
            assertThat(service.isRunning()).isTrue();
        } finally {
            service.onStop();
        }
    }

    @Test
    void changedConfigurationPublishesItsRevisionWithoutReloadingUnchangedAcl() throws Exception {
        AccessControl acl = new AccessControl();
        OrionDocument initial = OrionDocument.withAccessControl(acl);
        InMemoryStorage storage = new InMemoryStorage(new ConfigurationFile(
                serialize(initial), Optional.of("first-commit")));
        OrionDesiredState desiredState = new OrionDesiredState();
        OrionConfigurationEditor editor =
                new OrionConfigurationEditor(storage,
                new OrionConfiguration(),
                testCipher(),
                testMaterial(),
                desiredState);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                testServerIdentity(),
                desiredState,
                editor,
                Optional.empty());
        service.onStart();
        try {
            AccessControl publishedAcl = desiredState.current().document().system().accessControl();
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 8443,
                    URI.create("https://localhost:8443"), Optional.empty(), Optional.empty(),
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.empty());
            OrionDocument changed = new OrionDocument(new OrionDocument.SystemConfiguration(
                    acl, Optional.of(https), List.of(), List.of(), List.of()), List.of());
            storage.snapshot = new ConfigurationFile(serialize(changed),
                    Optional.of("second-commit"));

            storage.changeListener.accept("configuration changed");

            assertThat(desiredState.current().revision()).contains("second-commit");
            assertThat(desiredState.current().document().system().https()).contains(https);
            assertThat(desiredState.current().document().system().accessControl()).isSameAs(publishedAcl);
        } finally {
            service.onStop();
        }
    }

    @Test
    void invalidSecretEnvelopeRetainsTheLastPublishedSnapshot() throws Exception {
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        InMemoryStorage storage = new InMemoryStorage(new ConfigurationFile(
                serialize(initial), Optional.of("valid-commit")));
        OrionDesiredState desiredState = new OrionDesiredState();
        OrionConfigurationEditor editor =
                new OrionConfigurationEditor(storage,
                new OrionConfiguration(),
                testCipher(),
                testMaterial(),
                desiredState);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                testServerIdentity(),
                desiredState,
                editor,
                Optional.empty());
        service.onStart();
        try {
            OrionDesiredState.Snapshot lastValid = desiredState.current();
            OrionDocument candidate = new OrionDocument(new OrionDocument.SystemConfiguration(
                    new AccessControl(), Optional.empty(),
                    List.of(new ConfigurationSecret("invalid", "not-an-envelope")), List.of(), List.of()), List.of());
            storage.snapshot = new ConfigurationFile(serialize(candidate),
                    Optional.of("invalid-commit"));

            storage.changeListener.accept("invalid secret");

            assertThat(desiredState.current()).isSameAs(lastValid);
        } finally {
            service.onStop();
        }
    }

    @Test
    void listsCanonicalDeduplicatedSshCredentialsForOnlyTheSelectedUser() {
        AclFixture primary = new AclFixture();
        AclFixture.User alice = user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic()))
                .addCredential(AccessControl.CredentialType.ARGON2, "password-hash")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()));
        primary.getUsers().add(alice);
        primary.getUsers().add(user("bob")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_THREE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            SshCredentialListResult result = fixture.service.listSshCredentials("ALICE");

            assertThat(result).isInstanceOf(SshCredentialListResult.Success.class);
            assertThat(((SshCredentialListResult.Success) result).credentials())
                    .containsExactlyInAnyOrder(descriptor(KEY_ONE.getPublic()), descriptor(KEY_TWO.getPublic()))
                    .isSortedAccordingTo(java.util.Comparator.comparing(SshCredential::fingerprint));
        }
    }

    @Test
    void reportsMalformedStoredSshCredentialsWithoutHidingThem() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "not-a-key"));

        try (ServiceFixture fixture = fixture(primary)) {
            assertFailure(
                    fixture.service.listSshCredentials("alice"),
                    SshCredentialFailureCode.INVALID_STORED_KEY);
        }
    }

    @Test
    void atomicallyAddsCanonicalKeysToTheConfigurationFile() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "password-hash"));
        primary.getUsers().add(user("bob")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_THREE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            String commented = key(KEY_ONE.getPublic()) + " alice@example";
            SshCredentialUpdateResult first = fixture.addSshCredentials(
                    "alice",
                    List.of(commented, key(KEY_TWO.getPublic()), commented));
            SshCredentialUpdateResult second = fixture.addSshCredentials("alice", List.of(commented));

            assertThat(first).isInstanceOfSatisfying(SshCredentialUpdateResult.Success.class, success -> {
                assertThat(success.changed()).isTrue();
                assertThat(success.credentials()).hasSize(2);
            });
            assertThat(second).isInstanceOfSatisfying(
                    SshCredentialUpdateResult.Success.class,
                    success -> assertThat(success.changed()).isFalse());
            assertThat(fixture.storage.saveCount).isEqualTo(1);
            assertThat(sshValues(fixture.storage.snapshot, "alice"))
                    .containsExactlyInAnyOrder(key(KEY_ONE.getPublic()), key(KEY_TWO.getPublic()));
        }
    }

    @Test
    void invalidAdditionAndMissingUserDoNotMutateTheSnapshot() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice"));

        try (ServiceFixture fixture = fixture(primary)) {
            assertFailure(
                    fixture.addSshCredentials("alice", List.of(key(KEY_ONE.getPublic()), "invalid")),
                    SshCredentialFailureCode.INVALID_KEY);
            assertFailure(
                    fixture.addSshCredentials("missing", List.of(key(KEY_ONE.getPublic()))),
                    SshCredentialFailureCode.USER_NOT_FOUND);
            assertThat(fixture.storage.saveCount).isZero();
        }
    }

    @Test
    void mapsAStaleConditionalSaveToConcurrentUpdateWithoutActivatingTheDraft() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice"));

        try (ServiceFixture fixture = fixture(primary)) {
            fixture.storage.concurrentOnSave = true;

            assertFailure(
                    fixture.addSshCredentials("alice", List.of(key(KEY_ONE.getPublic()))),
                    SshCredentialFailureCode.CONCURRENT_UPDATE);
            assertThat(sshValues(fixture.storage.snapshot, "alice")).isEmpty();
        }
    }

    @Test
    void addedRootKeysRetainTheExistingAuthenticationGeneration() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root").addCredential(
                AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                "root-auth-generation:generation-one",
                key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            assertThat(fixture.addSshCredentials("root", List.of(key(KEY_TWO.getPublic()))))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);

            assertThat(credentials(fixture.storage.snapshot, "root"))
                    .filteredOn(credential -> credential.getType() == AccessControl.CredentialType.OPENSSH_PUBLIC_KEY)
                    .extracting(AccessControl.Credential::getKeyId)
                    .containsOnly("root-auth-generation:generation-one");
        }
    }

    @Test
    void removesEveryDuplicateOfAUniqueKeyWithoutTouchingOtherCredentialsOrUsers() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic()))
                .addCredential(AccessControl.CredentialType.ARGON2, "password-hash"));
        primary.getUsers().add(user("bob")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            SshCredentialUpdateResult removed = fixture.removeSshCredential(
                    "alice",
                    descriptor(KEY_ONE.getPublic()).fingerprint(),
                    false);

            assertThat(removed).isInstanceOfSatisfying(
                    SshCredentialUpdateResult.Success.class,
                    success -> assertThat(success.credentials()).containsExactly(descriptor(KEY_TWO.getPublic())));
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .filteredOn(credential -> credential.getType() == AccessControl.CredentialType.ARGON2)
                    .singleElement()
                    .extracting(AccessControl.Credential::getValue)
                    .isEqualTo("password-hash");
            assertThat(sshValues(fixture.storage.snapshot, "bob"))
                    .containsExactly(key(KEY_ONE.getPublic()));
        }
    }

    @Test
    void removalRejectsMissingAmbiguousMalformedAndUnforcedLastKeyWithoutSaving() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            String first = descriptor(KEY_ONE.getPublic()).fingerprint();
            String second = descriptor(KEY_TWO.getPublic()).fingerprint();
            assertFailure(
                    fixture.removeSshCredential("alice", commonPrefix(first, second), false),
                    SshCredentialFailureCode.AMBIGUOUS_MATCH);
            assertFailure(
                    fixture.removeSshCredential("alice", "SHA256:missing", false),
                    SshCredentialFailureCode.MISSING_MATCH);
            assertThat(fixture.storage.saveCount).isZero();

            assertThat(fixture.removeSshCredential("alice", first, false))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertFailure(
                    fixture.removeSshCredential("alice", second, false),
                    SshCredentialFailureCode.LAST_KEY_REQUIRES_FORCE);
            assertThat(fixture.storage.saveCount).isEqualTo(1);
        }

        AclFixture malformed = new AclFixture();
        malformed.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, "not-a-key"));
        try (ServiceFixture fixture = fixture(malformed)) {
            assertFailure(
                    fixture.removeSshCredential("alice", "SHA256:any", true),
                    SshCredentialFailureCode.INVALID_STORED_KEY);
            assertThat(fixture.storage.saveCount).isZero();
        }
    }

    @Test
    void forcedNonRootRemovalCanRemoveTheLastKeyAndRepeatingItIsMissing() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            String fingerprint = descriptor(KEY_ONE.getPublic()).fingerprint();
            assertThat(fixture.removeSshCredential("alice", fingerprint, true))
                    .isInstanceOfSatisfying(
                            SshCredentialUpdateResult.Success.class,
                            success -> assertThat(success.credentials()).isEmpty());
            assertFailure(
                    fixture.removeSshCredential("alice", fingerprint, true),
                    SshCredentialFailureCode.MISSING_MATCH);
        }
    }

    @Test
    void rootRemovalPreservesGenerationUntilForcedLastKeyCreatesFailClosedState() {
        String generation = "generation-one";
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("RoOt")
                .addCredential(
                        AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                        "root-auth-generation:" + generation,
                        key(KEY_ONE.getPublic()))
                .addCredential(
                        AccessControl.CredentialType.OPENSSH_PUBLIC_KEY,
                        "root-auth-generation:" + generation,
                        key(KEY_TWO.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            AuthenticationResult authentication = fixture.service.authenticateSshUser(
                    "root",
                    KEY_ONE.getPublic().getEncoded());
            assertThat(authentication).isInstanceOf(AuthenticationResult.Success.class);
            TokenRefreshResult issued = fixture.service.refreshToken(
                    (AuthenticationResult.Success) authentication,
                    60);
            assertThat(issued).isInstanceOf(TokenRefreshResult.Success.class);

            assertThat(fixture.removeSshCredential(
                    "root",
                    descriptor(KEY_ONE.getPublic()).fingerprint(),
                    false)).isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertThat(credentials(fixture.storage.snapshot, "root"))
                    .extracting(AccessControl.Credential::getKeyId)
                    .containsOnly("root-auth-generation:" + generation);

            assertThat(fixture.removeSshCredential(
                    "root",
                    descriptor(KEY_TWO.getPublic()).fingerprint(),
                    true)).isInstanceOf(SshCredentialUpdateResult.Success.class);

            assertFailure(
                    fixture.addSshCredentials("root", List.of(key(KEY_THREE.getPublic()))),
                    SshCredentialFailureCode.ROOT_LOCKED);
            assertThat(fixture.service.authenticateSshUser("root", KEY_TWO.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.authenticateUser("root", "anything".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            String token = ((TokenRefreshResult.Success) issued).token();
            assertThat(fixture.service.verifyToken(token.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);
            assertThat(fixture.service.refreshToken(
                    (AuthenticationResult.Success) authentication,
                    60)).isInstanceOf(TokenRefreshResult.Failure.class);
            assertThat(sshValues(fixture.storage.snapshot, "root")).isEmpty();
            assertThat(credentials(fixture.storage.snapshot, "root"))
                    .singleElement()
                    .extracting(AccessControl.Credential::getKeyId)
                    .asString()
                    .startsWith("root-auth-locked:");
        }
    }

    @Test
    void tokenAuthenticationReturnsValidatedTokenIdentity() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            AuthenticationResult authentication = fixture.service.authenticateSshUser(
                    "alice",
                    KEY_ONE.getPublic().getEncoded());
            TokenRefreshResult issued = fixture.service.refreshToken(
                    (AuthenticationResult.Success) authentication,
                    60);
            String token = ((TokenRefreshResult.Success) issued).token();

            TokenAuthenticationResult result = fixture.service.verifyToken(
                    token.getBytes(StandardCharsets.UTF_8));
            assertThat(result).isInstanceOf(TokenAuthenticationResult.Success.class);
            TokenAuthenticationResult.Success success = (TokenAuthenticationResult.Success) result;
            assertThat(success.tokenIdentity().tokenId()).isNotBlank();
            assertThat(success.tokenIdentity().subject()).isEqualTo("alice");
        }
    }

    @Test
    void lockedRootIsSkippedByEveryPublicKeyResolver() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root")
                .addCredential(
                        AccessControl.CredentialType.ARGON2,
                        "root-auth-locked:generation",
                        "locked-hash")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            assertThat(fixture.service.authenticateSshUser("root", KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.authenticateGitSshKey(KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOfSatisfying(
                            AuthenticationResult.Success.class,
                            success -> assertThat(success.userIdentity().getUserId()).isEqualTo("alice"));
        }
    }

    @Test
    void adminUserUpdateWritesOnlyTheConfigurationFile() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "old-hash"));

        try (ServiceFixture fixture = fixture(primary)) {
            fixture.createOrUpdateUser(userUpdate("alice", "new-hash"));

            assertThat(parse(fixture.storage.snapshot.content()).getUsers())
                    .extracting(AccessControl.User::getId).containsExactlyInAnyOrder("root", "alice");
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .singleElement()
                    .extracting(AccessControl.Credential::getValue)
                    .isEqualTo("new-hash");
        }
    }

    @Test
    void adminUpdateWaitsForCredentialMutationAndCannotResurrectRootKey() throws Exception {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "old-hash"));

        try (ServiceFixture fixture = fixture(primary);
             var executor = Executors.newFixedThreadPool(2)) {
            fixture.storage.blockCredentialRemoval = true;
            var removal = executor.submit(() -> fixture.removeSshCredential(
                    "root",
                    descriptor(KEY_ONE.getPublic()).fingerprint(),
                    true));
            assertThat(fixture.storage.credentialRemovalSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch adminStarted = new CountDownLatch(1);
            var adminUpdate = executor.submit(() -> {
                adminStarted.countDown();
                fixture.createOrUpdateUser(userUpdate("alice", "new-hash"));
            });
            assertThat(adminStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(adminUpdate.isDone()).isFalse();

            fixture.storage.continueCredentialRemoval.countDown();

            assertThat(removal.get()).isInstanceOf(SshCredentialUpdateResult.Success.class);
            adminUpdate.get();
            assertThat(sshValues(fixture.storage.snapshot, "root")).isEmpty();
            assertThat(credentials(fixture.storage.snapshot, "root"))
                    .extracting(AccessControl.Credential::getKeyId)
                    .singleElement()
                    .asString()
                    .startsWith("root-auth-locked:");
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .singleElement()
                    .extracting(AccessControl.Credential::getValue)
                    .isEqualTo("new-hash");
        }
    }

    @Test
    void internalServerKeySynchronizationKeepsOtherUsersInTheConfigurationFile() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "alice-hash"));
        primary.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(
                primary,
                testServerIdentity(List.of(KEY_THREE.getPublic())))) {
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .singleElement().extracting(AccessControl.Credential::getValue).isEqualTo("alice-hash");
            assertThat(fixture.service.listSshCredentials("root"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Success.class, success ->
                            assertThat(success.credentials()).containsExactlyInAnyOrder(
                                    descriptor(KEY_ONE.getPublic()),
                                    descriptor(KEY_THREE.getPublic())));
        }
    }

    @Test
    void internalServerKeySynchronizationDoesNotReloadInsideSaveNotification() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        InMemoryStorage storage = new InMemoryStorage(
                new ConfigurationFile(serialize(primary.toAccessControl()), Optional.of("version-one")));
        storage.notifyDuringSave = true;
        OrionDesiredState desired = new OrionDesiredState();
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage, new OrionConfiguration(),
                testCipher(), testMaterial(), desired);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(), OrionRuntimeOptions.defaults(),
                testServerIdentity(List.of(KEY_THREE.getPublic())), desired, editor, Optional.empty());

        try {
            service.onStart();
            assertThat(storage.saveCount).isEqualTo(1);
            assertThat(storage.loadDuringSave).isFalse();
            assertThat(desired.current().revision()).isEqualTo(storage.snapshot.revision());
            assertThat(service.listSshCredentials("root"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Success.class, success ->
                            assertThat(success.credentials()).contains(descriptor(KEY_THREE.getPublic())));
        } finally {
            service.onStop();
        }
    }

    @Test
    void internalServerKeySynchronizationRetriesAgainstConcurrentConfiguration() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        AclFixture winning = new AclFixture();
        winning.getUsers().add(user("root")
                .addCredential(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        winning.getUsers().add(user("alice")
                .addCredential(AccessControl.CredentialType.ARGON2, "alice-hash"));
        InMemoryStorage storage = new InMemoryStorage(
                new ConfigurationFile(serialize(initial.toAccessControl()), Optional.of("version-one")));
        storage.concurrentReplacement = new ConfigurationFile(
                serialize(winning.toAccessControl()), Optional.of("version-two"));
        OrionDesiredState desired = new OrionDesiredState();
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage, new OrionConfiguration(),
                testCipher(), testMaterial(), desired);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(), OrionRuntimeOptions.defaults(),
                testServerIdentity(List.of(KEY_THREE.getPublic())), desired, editor, Optional.empty());

        try {
            service.onStart();
            assertThat(storage.saveCount).isEqualTo(1);
            assertThat(desired.current().revision()).contains("version-two");
            assertThat(credentials(storage.snapshot, "alice"))
                    .singleElement().extracting(AccessControl.Credential::getValue).isEqualTo("alice-hash");
            assertThat(service.listSshCredentials("root"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Success.class, success ->
                            assertThat(success.credentials()).contains(descriptor(KEY_THREE.getPublic())));
        } finally {
            service.onStop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsDefaultOrganizationOnlyForNewConfigurationAndPreservesItOnRestart(boolean resetRoot)
            throws Exception {
        AtomicReference<ConfigurationFile> persisted = new AtomicReference<>();
        OrionConfigurationStorage storage = new OrionConfigurationStorage() {
            @Override
            public Result<ConfigurationFile> load() {
                ConfigurationFile file = persisted.get();
                return file == null
                        ? new Result.Failure<>(Result.FailureCode.NOT_FOUND)
                        : new Result.Success<>(file);
            }

            @Override
            public void save(ConfigurationFile snapshot, String message, UserEmail author) {
                persisted.set(snapshot);
            }

            @Override
            public boolean createIfMissing() {
                return true;
            }
        };
        OrionDesiredState desired = new OrionDesiredState();
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                new OrionRuntimeOptions(resetRoot),
                testServerIdentity(),
                desired,
                new OrionConfigurationEditor(storage, new OrionConfiguration(), testCipher(), testMaterial(), desired),
                Optional.empty());
        PrintStream originalOut = System.out;
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(output);
            service.onStart();
            OrionDocument created = parseDocument(persisted.get().content());
            assertThat(created.organizations()).extracting(org -> org.id().value()).containsExactly("default");
            assertThat(created.organizations().getFirst().users()).isEmpty();
            assertThat(created.system().accessControl().getUsers()).extracting(AccessControl.User::getId)
                    .contains("root");
            service.onStop();
            byte[] beforeRestart = persisted.get().content();
            OrionAccessControlServiceImpl restarted = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                testServerIdentity(),
                desired,
                new OrionConfigurationEditor(storage, new OrionConfiguration(), testCipher(), testMaterial(), desired),
                Optional.empty());
            try {
                restarted.onStart();
                assertThat(persisted.get().content()).isEqualTo(beforeRestart);
                assertThat(desired.current().document().organizations()).isEqualTo(created.organizations());
            } finally {
                restarted.onStop();
            }
        } finally {
            System.setOut(originalOut);
            service.onStop();
        }
    }

    @Test
    void doesNotInsertDefaultOrganizationIntoExistingConfiguration() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            assertThat(parseDocument(fixture.storage.snapshot.content()).organizations()).isEmpty();
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
            ConfigurationFile initial,
            OrionRuntimeOptions runtimeOptions) {
        FailingReloadStorage storage = new FailingReloadStorage(initial);
        OrionDesiredState configurationState = new OrionDesiredState();
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                runtimeOptions,
                testServerIdentity(),
                configurationState,
                new OrionConfigurationEditor(storage, new OrionConfiguration(), testCipher(), testMaterial(), configurationState),
                Optional.empty());
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(processOutput, true, StandardCharsets.UTF_8));

            assertThatThrownBy(service::onStart)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Configuration repository not initialized");
        } finally {
            System.setOut(originalOut);
            service.onStop();
        }

        assertThat(storage.saved).isTrue();
        assertThat(processOutput.toString(StandardCharsets.UTF_8)).doesNotContain("---ROOT PASSWORD: ");
    }

    private static ConfigurationFile defaultAclSnapshot() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(
                ACLUtil.generateDefaultAccessControl("old-password-hash")), output);
        return new ConfigurationFile(output.toByteArray(), Optional.of("initial"));
    }

    @Test
    void userUpdatePersistsReadWriteGrantWithoutSeparateReadFlag() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            fixture.createOrUpdateUser(new AccessControlUserUpdate(
                    "alice", "alice@example.test", List.of(),
                    List.of(new AccessControlRepositoryGrantUpdate("project", false, true, false, false, "dev"))));

            AccessControl persisted = parse(fixture.storage.snapshot.content());
            AccessControl.User alice = persisted.getUsers().stream()
                    .filter(user -> user.getId().equals("alice")).findFirst().orElseThrow();
            assertThat(alice.getGrants().getFirst().getInfo())
                    .extracting(AccessControl.GrantExpression::getKey)
                    .contains(AccessControl.GrantKey.READ_WRITE);
        }
    }

    @Test
    void reportsInvalidUserInputWithoutMutatingStorage() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            ConfigurationFile original = fixture.storage.snapshot;
            assertThatThrownBy(() -> fixture.createOrUpdateUser(
                    new AccessControlUserUpdate(" ", "", List.of(), List.of())))
                    .isInstanceOf(AccessControlValidationException.class);
            assertThatThrownBy(() -> fixture.createOrUpdateUser(new AccessControlUserUpdate(
                    "alice", "", List.of(), List.of(
                            new AccessControlRepositoryGrantUpdate("", true, false, false, false, "main")))))
                    .isInstanceOf(AccessControlValidationException.class);
            assertThat(fixture.storage.snapshot).isSameAs(original);
        }
    }

    private static ServiceFixture fixture(AclFixture draft) {
        return fixture(draft, testServerIdentity());
    }

    private static ServiceFixture fixture(
            AclFixture draft,
            ServerIdentityCapability serverIdentity) {
        return fixture(draft, serverIdentity, new OrionPasswordHashingService());
    }

    private static ServiceFixture fixture(
            AclFixture draft,
            ServerIdentityCapability serverIdentity,
            OrionPasswordHashingService hashing) {
        InMemoryStorage storage = new InMemoryStorage(
                new ConfigurationFile(serialize(draft.toAccessControl()), Optional.of("version-one")));
        OrionDesiredState configurationState = new OrionDesiredState();
        OrionConfigurationEditor editor =
                new OrionConfigurationEditor(storage,
                new OrionConfiguration(),
                testCipher(),
                testMaterial(),
                configurationState);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(storage,
                hashing,
                OrionRuntimeOptions.defaults(),
                serverIdentity,
                configurationState,
                editor,
                Optional.empty());
        service.onStart();
        return new ServiceFixture(service, storage, editor);
    }

    private static AclFixture.User user(String id) {
        AclFixture.User user = new AclFixture.User();
        user.setId(id);
        user.setEmail(id + "@example.test");
        return user;
    }

    private static final class AclFixture {
        private final List<User> users = new ArrayList<>();
        private final List<Role> roles = new ArrayList<>();
        private final List<Grant> grants = new ArrayList<>();

        List<User> getUsers() { return users; }
        List<Role> getRoles() { return roles; }
        List<Grant> getGrants() { return grants; }

        AccessControl toAccessControl() {
            List<AccessControl.User> immutableUsers = new ArrayList<>();
            for (User user : users) immutableUsers.add(user.toAccessControl());
            List<AccessControl.Role> immutableRoles = new ArrayList<>();
            for (Role role : roles) immutableRoles.add(role.toAccessControl());
            List<AccessControl.Grant> immutableGrants = new ArrayList<>();
            for (Grant grant : grants) immutableGrants.add(grant.toAccessControl());
            return new AccessControl(immutableUsers, immutableRoles, immutableGrants);
        }

        private static final class User {
            private String id;
            private String email;
            private final List<AccessControl.Credential> credentials = new ArrayList<>();
            private final List<String> roles = new ArrayList<>();

            void setId(String id) { this.id = id; }
            void setEmail(String email) { this.email = email; }
            List<String> getRoles() { return roles; }

            User addCredential(AccessControl.CredentialType type, String value) {
                return addCredential(type, null, value);
            }

            User addCredential(AccessControl.CredentialType type, String keyId, String value) {
                credentials.add(new AccessControl.Credential(type, keyId, value));
                return this;
            }

            User addRole(String role) {
                roles.add(role);
                return this;
            }

            AccessControl.User toAccessControl() {
                return new AccessControl.User(id, null, null, email, credentials, roles, List.of());
            }
        }

        private static final class Role {
            private String id;
            private final List<Grant> grants = new ArrayList<>();
            private final List<String> references = new ArrayList<>();

            void setId(String id) { this.id = id; }
            void addGrant(Grant grant) { grants.add(grant); }
            void addGrantReference(String id) { references.add(id); }

            AccessControl.Role toAccessControl() {
                List<AccessControl.Grant> immutableGrants = new ArrayList<>();
                for (Grant grant : grants) immutableGrants.add(grant.toAccessControl());
                return new AccessControl.Role(id, immutableGrants, references);
            }
        }

        private static final class Grant {
            private String id;
            private final List<AccessControl.GrantExpression> info = new ArrayList<>();

            void setId(String id) { this.id = id; }
            String getId() { return id; }
            List<AccessControl.GrantExpression> getInfo() { return info; }

            Grant addKey(AccessControl.GrantKey key, String value) {
                info.add(new AccessControl.GrantExpression(key, value));
                return this;
            }

            AccessControl.Grant toAccessControl() {
                return new AccessControl.Grant(id, info);
            }
        }
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
            OrionXml.write(OrionDocument.withAccessControl(accessControl), output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] serialize(OrionDocument document) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            OrionXml.write(document, output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OrionDocument parseDocument(byte[] content) {
        try {
            return OrionXml.read(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AccessControl parse(byte[] content) {
        try {
            return OrionXml.read(new java.io.ByteArrayInputStream(content)).system().accessControl();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> sshValues(ConfigurationFile file, String userId) {
        for (AccessControl.User user : parse(file.content()).getUsers()) {
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
            ConfigurationFile file,
            String userId) {
        for (AccessControl.User user : parse(file.content()).getUsers()) {
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

    private static String testEnvelope() {
        try {
            return new ConfigurationSecretEnvelopeCodec().serialize(new ConfigurationSecretEnvelope(1,
                    new KeyMaterialAlias("configuration-v1"), new KeyMaterialVersion(1),
                    ConfigurationSecretEnvelopeCodec.AES_WRAP, ConfigurationSecretEnvelopeCodec.AES_GCM,
                    ConfigurationSecretEnvelopeCodec.BASE64_URL, new byte[]{1}, new byte[]{2}, new byte[]{3}));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ConfigurationCipherCapability testCipher() {
        return new ConfigurationCipherCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context) {
                return new byte[]{'x'};
            }
        };
    }

    private static ConfigurationMaterialCapability testMaterial() {
        return new ConfigurationMaterialCapability() {
            @Override
            public void require(KeyMaterialDescriptor descriptor) {
            }

            @Override
            public void require(TrustedCertificateDescriptor descriptor) {
            }
        };
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
            OrionConfigurationEditor editor) implements AutoCloseable {
        private void createOrUpdateUser(AccessControlUserUpdate update) {
            try (OrionConfigurationEdit edit = editor.edit()) {
                service.createOrUpdateUser(edit, update);
                edit.apply("createOrUpdateUser() " + update.id(), new UserEmail(update.id(), update.email()));
            }
        }

        private SshCredentialUpdateResult addSshCredentials(String userId, List<String> keys) {
            return mutate(userId, "add SSH credentials", edit -> service.addSshCredentials(edit, userId, keys));
        }

        private SshCredentialUpdateResult removeSshCredential(String userId, String prefix, boolean force) {
            return mutate(userId, "remove SSH credential",
                    edit -> service.removeSshCredential(edit, userId, prefix, force));
        }

        private SshCredentialUpdateResult mutate(String userId, String message,
                java.util.function.Function<OrionConfigurationEdit,
                        SshCredentialUpdateResult> mutation) {
            try (OrionConfigurationEdit edit = editor.edit()) {
                SshCredentialUpdateResult result = mutation.apply(edit);
                if (result instanceof SshCredentialUpdateResult.Success success && success.changed()) {
                    edit.apply(message + " for " + userId, new UserEmail(userId, ""));
                }
                return result;
            } catch (OrionConfigurationConcurrentUpdateException failure) {
                return SshCredentialUpdateResult.failure(SshCredentialFailureCode.CONCURRENT_UPDATE,
                        "Configuration changed", List.of(), failure);
            } catch (RuntimeException failure) {
                return SshCredentialUpdateResult.failure(SshCredentialFailureCode.PERSISTENCE_FAILED,
                        "Cannot save configuration", List.of(), failure);
            }
        }

        @Override
        public void close() {
            service.onStop();
        }
    }

    private static final class InMemoryStorage implements OrionConfigurationStorage {
        private Consumer<String> changeListener = ignored -> {};
        private boolean notifyDuringSave;
        private boolean saving;
        private boolean loadDuringSave;
        private ConfigurationFile concurrentReplacement;

        @Override
        public ChangeSubscription onChange(Consumer<String> listener) {
            changeListener = listener;
            return () -> changeListener = ignored -> {};
        }

        private volatile ConfigurationFile snapshot;
        private int saveCount;
        private int loadCount;
        private boolean concurrentOnSave;
        private boolean loadUnavailable;
        private boolean blockCredentialRemoval;
        private final CountDownLatch credentialRemovalSaveEntered = new CountDownLatch(1);
        private final CountDownLatch continueCredentialRemoval = new CountDownLatch(1);

        private InMemoryStorage(ConfigurationFile snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Result<ConfigurationFile> load() {
            loadCount++;
            if (saving) loadDuringSave = true;
            if (loadUnavailable) {
                return new Result.Failure<>(Result.FailureCode.GENERAL);
            }
            return new Result.Success<>(snapshot);
        }

        @Override
        public void save(ConfigurationFile snapshot, String message, UserEmail author) {
            if (concurrentReplacement != null) {
                this.snapshot = concurrentReplacement;
                concurrentReplacement = null;
                throw new OrionConfigurationConcurrentUpdateException("simulated winning revision", null);
            }
            if (blockCredentialRemoval && message.startsWith("remove SSH credential")) {
                credentialRemovalSaveEntered.countDown();
                try {
                    continueCredentialRemoval.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while blocking credential removal", error);
                }
            }
            if (concurrentOnSave) {
                throw new OrionConfigurationConcurrentUpdateException("simulated race", null);
            }
            if (!this.snapshot.revision().equals(snapshot.revision())) {
                throw new IllegalStateException("version conflict");
            }
            this.snapshot = snapshot;
            saveCount++;
            if (notifyDuringSave) {
                saving = true;
                try {
                    changeListener.accept("synchronous save notification");
                } finally {
                    saving = false;
                }
            }
        }

    }

    private static final class FailingReloadStorage implements OrionConfigurationStorage {
        private final ConfigurationFile initial;
        private boolean saved;

        private FailingReloadStorage(ConfigurationFile initial) {
            this.initial = initial;
        }

        @Override
        public Result<ConfigurationFile> load() {
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
        public void save(ConfigurationFile snapshot, String message, UserEmail author) {
            saved = true;
        }

        @Override
        public boolean createIfMissing() {
            return true;
        }
    }
}
