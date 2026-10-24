package pro.deta.orion.acl;

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
import pro.deta.orion.schema.orion.v2.RepositoryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
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
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.ConfigurationSecretCipher;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.ConfigurationSecretContext;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelope;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelopeCodec;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;
import pro.deta.orion.auth.DefaultAccessControl;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.Role;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.util.Result;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.util.Subscription;
import pro.deta.orion.config.ConfigurationChanged;
import pro.deta.orion.config.OrionConfigurationModification;
import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.ChangeMetadata;
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
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionAccessControlServiceImplTest {
    private static final SigningMaterialSet SIGNING = new SigningMaterialSet(
            new KeyMaterialDescriptor("test-signing-key", KeyMaterialPurpose.SERVER_SIGNING,
                    KeyMaterialAlgorithm.RSA, 1), List.of());
    private static final KeyPair KEY_ONE = keyPair("RSA", 2048);
    private static final KeyPair KEY_TWO = keyPair("EC", 256);
    private static final KeyPair KEY_THREE = keyPair("RSA", 2048);

    @Test
    void systemOidcPreservesRootKeyGenerationAndRejectsLockedOrRecoveryRoot() {
        String issuer = "https://sso.example.test";
        OidcProvider provider = new OidcProvider("sso", URI.create(issuer), "client", "secret", 172800, 0);
        Credential binding = new Credential(CredentialType.OIDC_SUBJECT, issuer, "external-root");
        java.util.function.Function<List<Credential>, OrionDocument> document =
                credentials ->
                        new OrionDocument(
                                new OrionDocument.SystemConfiguration(
                                        new AccessControl(
                                                List.of(
                                                        new User(
                                                                "root",
                                                                null,
                                                                null,
                                                                null,
                                                                credentials,
                                                                List.of(),
                                                                List.of())),
                                                List.of(),
                                                List.of()),
                                        Optional.empty(),
                                        List.of(new ConfigurationSecret("secret", testEnvelope())),
                                        List.of(),
                                        List.of(),
                                        List.of(provider)),
                                List.of());
        try (ServiceFixture fixture = fixture(document.apply(List.of(binding,
                new Credential(CredentialType.OPENSSH_PUBLIC_KEY,
                        "root-auth-generation:first", key(KEY_ONE.getPublic())))))) {
            OrionAccessControlServiceImpl service = fixture.service;
            Consumer<List<Credential>> publish = credentials -> {
                fixture.storage.save(fixture.storage.snapshot.revision(), document.apply(credentials),
                        ChangeMetadata.EMPTY);
                fixture.storage.awaitEvents();
            };
        assertThat(service.authenticateSshUser("root", KEY_ONE.getPublic().getEncoded()))
                .isInstanceOf(AuthenticationResult.Success.class);
        TokenIssueResult issued = service.issueSystemOidcToken("root", provider, "external-root", 60);
        assertThat(issued).isInstanceOf(TokenIssueResult.Success.class);
        byte[] token = ((TokenIssueResult.Success) issued).token().getBytes(StandardCharsets.UTF_8);
        assertThat(service.verifyToken(token)).isInstanceOf(TokenAuthenticationResult.Success.class);
        publish.accept(List.of(binding, new Credential(CredentialType.OPENSSH_PUBLIC_KEY,
                "root-auth-generation:second", key(KEY_ONE.getPublic()))));
        assertThat(service.verifyToken(token)).isInstanceOf(TokenAuthenticationResult.Failure.class);
        for (String generation : List.of("root-auth-locked:locked", "root-auth-generation:recovery")) {
            publish.accept(List.of(binding, new Credential(CredentialType.ARGON2, generation, "hash")));
            assertThat(service.findSystemOidcUser(issuer, "external-root")).isNull();
            assertThat(service.issueSystemOidcToken("root", provider, "external-root", 60))
                    .isInstanceOf(TokenIssueResult.Failure.class);
            assertThat(service.verifyToken(token)).isInstanceOf(TokenAuthenticationResult.Failure.class);
        }
        }
    }

    @Test
    void systemOidcReloadUsesCurrentGrantsAndRejectsRotatedOrAmbiguousBindings() {
        String issuer = "https://sso.example.test";
        OidcProvider provider = new OidcProvider("sso", URI.create(issuer), "client", "secret", 172800, 0);
        Credential binding = new Credential(CredentialType.OIDC_SUBJECT, issuer, "external-alice");
        User alice = new User("alice", null, null, null, List.of(binding), List.of("reader"), List.of());
        Grant read = new Grant("read", List.of(new GrantExpression(GrantKey.READ, "true")));
        Grant admin = new Grant("admin", List.of(new GrantExpression(GrantKey.ADMIN, "true")));
        java.util.function.BiFunction<AccessControl, String, OrionDocument> document = (acl, envelope) ->
                new OrionDocument(new OrionDocument.SystemConfiguration(acl, Optional.empty(),
                        List.of(new ConfigurationSecret("secret", envelope)), List.of(), List.of(),
                        List.of(provider)), List.of());
        AccessControl original = new AccessControl(List.of(alice),
                List.of(new Role("reader", List.of(read), List.of())), List.of());
        try (ServiceFixture fixture = fixture(document.apply(original, testEnvelope()))) {
            OrionAccessControlServiceImpl service = fixture.service;
            java.util.function.BiConsumer<AccessControl, String> publish = (acl, envelope) -> {
                fixture.storage.save(fixture.storage.snapshot.revision(), document.apply(acl, envelope),
                        ChangeMetadata.EMPTY);
                fixture.storage.awaitEvents();
            };
        TokenIssueResult.Success issued = (TokenIssueResult.Success) service.issueSystemOidcToken(
                "alice", provider, "external-alice", 60);
        byte[] token = issued.token().getBytes(StandardCharsets.UTF_8);
        TokenAuthenticationResult.Success authenticated = (TokenAuthenticationResult.Success) service.verifyToken(token);
        assertThat(authenticated.userIdentity().getGrants()).containsExactly(read);

        AccessControl changedGrants = new AccessControl(List.of(alice),
                List.of(new Role("reader", List.of(admin), List.of())), List.of());
        publish.accept(changedGrants, testEnvelope());
        assertThat(service.verifyToken(token)).isInstanceOfSatisfying(TokenAuthenticationResult.Success.class,
                result -> assertThat(result.userIdentity().getGrants()).containsExactly(admin));
        assertThat(authenticated.userIdentity().getGrants()).containsExactly(read);

        publish.accept(changedGrants, testEnvelope(4));
        assertThat(service.verifyToken(token)).isInstanceOf(TokenAuthenticationResult.Failure.class);
        TokenIssueResult.Success refreshed = (TokenIssueResult.Success) service.issueSystemOidcToken(
                "alice", provider, "external-alice", 60);
        byte[] refreshedToken = refreshed.token().getBytes(StandardCharsets.UTF_8);
        assertThat(service.verifyToken(refreshedToken)).isInstanceOf(TokenAuthenticationResult.Success.class);

        User duplicate = new User("bob", null, null, null, List.of(binding), List.of(), List.of());
            publish.accept(
                    new AccessControl(List.of(alice, duplicate), changedGrants.roles(), List.of()),
                    testEnvelope(4));
        assertThat(service.systemOidcGeneration("alice", provider, "external-alice")).isEmpty();
        assertThat(service.verifyToken(refreshedToken)).isInstanceOf(TokenAuthenticationResult.Failure.class);
        assertThat(service.issueSystemOidcToken("alice", provider, "external-alice", 60))
                .isInstanceOf(TokenIssueResult.Failure.class);
        }
    }

    @Test
    void stagesTwoSshKeysAndPublishesOnlyOnce() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("alice"));
        List<OrionDocumentRevision> published = new ArrayList<>();
        try (ServiceFixture fixture = fixture(initial);
                Subscription subscription = fixture.editor.subscribe(published::add)) {
            fixture.withAccess(
                    fixture.editor.edit(
                            fixture.storage.snapshot.revision(),
                            new ChangeMetadata(
                                    new UserEmail("alice", "alice@example.test"),
                                    "add two SSH credentials")),
                    edit -> {
                        assertThat(
                                        edit.addSshCredentials(
                                                "alice", List.of(key(KEY_ONE.getPublic()))))
                                .isInstanceOf(SshCredentialUpdateResult.Success.class);
                        assertThat(
                                        edit.addSshCredentials(
                                                "alice", List.of(key(KEY_THREE.getPublic()))))
                                .isInstanceOf(SshCredentialUpdateResult.Success.class);
                        edit.update(
                                document ->
                                        new OrionDocument(
                                                new OrionDocument.SystemConfiguration(
                                                        document.system().accessControl(),
                                                        document.system().https(),
                                                        List.of(
                                                                new ConfigurationSecret(
                                                                        "combined",
                                                                        testEnvelope())),
                                                        document.system().proxies(),
                                                        document.system().connections(),
                                                        document.system().oidcProviders()),
                                                document.organizations()));
                        assertThat(fixture.storage.saveCount).isZero();
                        fixture.storage.awaitEvents();
                        assertThat(published).isEmpty();
                        assertThat(
                                        fixture.service.authenticateSshUser(
                                                "alice", KEY_ONE.getPublic().getEncoded()))
                                .isInstanceOf(AuthenticationResult.Failure.class);
                        edit.apply();
                        assertThat(fixture.storage.saveCount).isEqualTo(1);
                        fixture.storage.awaitEvents();
                        assertThat(published).containsExactly(fixture.storage.snapshot);
                        assertThat(fixture.storage.snapshot.result()
                                .valueOrFailure("Cannot read configuration document")
                                .system().secrets())
                                .extracting(ConfigurationSecret::id)
                                .containsExactly("combined");
                        assertThat(
                                        fixture.service.authenticateSshUser(
                                                "alice", KEY_ONE.getPublic().getEncoded()))
                                .isInstanceOf(AuthenticationResult.Success.class);
                        assertThat(
                                        fixture.service.authenticateSshUser(
                                                "alice", KEY_THREE.getPublic().getEncoded()))
                                .isInstanceOf(AuthenticationResult.Success.class);
                        return null;
                    });
        }
    }

    @Test
    void withAccessDiscardsChangesAndKeepsTheOriginalException() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            OrionConfigurationModification edit =
                    fixture.editor.edit(
                            fixture.storage.snapshot.revision(),
                            new ChangeMetadata(UserEmail.EMPTY, "abandoned update"));
            IllegalStateException failure = new IllegalStateException("callback failed");
            assertThatThrownBy(() -> fixture.withAccess(edit, access -> {
                access.createOrUpdateUser(userUpdate("alice", "hash"));
                access.update(document -> new OrionDocument(new OrionDocument.SystemConfiguration(
                        document.system().accessControl(), document.system().https(),
                        List.of(new ConfigurationSecret("abandoned", testEnvelope())),
                        document.system().proxies(), document.system().connections(),
                        document.system().oidcProviders()), document.organizations()));
                throw failure;
            })).isSameAs(failure);
            assertThat(fixture.storage.saveCount).isZero();
            assertThat(fixture.service.userExists("alice")).isFalse();
            assertThatThrownBy(edit::document).isInstanceOf(IllegalStateException.class);
            edit.discard();
        }
    }

    @Test
    void discardedAndConflictingEditsPublishNothing() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("alice"));
        List<OrionDocumentRevision> published = new ArrayList<>();
        try (ServiceFixture fixture = fixture(initial);
                Subscription subscription = fixture.editor.subscribe(published::add)) {
            fixture.withAccess(
                    fixture.editor.edit(
                            fixture.storage.snapshot.revision(),
                            new ChangeMetadata(UserEmail.EMPTY, "")),
                    edit -> {
                        edit.addSshCredentials("alice", List.of(key(KEY_ONE.getPublic())));
                        return null;
                    });
            assertThat(fixture.storage.saveCount).isZero();
            fixture.withAccess(
                    fixture.editor.edit(
                            fixture.storage.snapshot.revision(),
                            new ChangeMetadata(UserEmail.EMPTY, "conflicting edit")),
                    edit -> {
                        edit.addSshCredentials("alice", List.of(key(KEY_TWO.getPublic())));
                        fixture.storage.concurrentOnSave = true;
                        assertThatThrownBy(() -> edit.apply())
                                .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
                        return null;
                    });
            assertThat(fixture.storage.saveCount).isZero();
            assertThat(fixture.service.authenticateSshUser("alice", KEY_ONE.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(fixture.service.authenticateSshUser("alice", KEY_TWO.getPublic().getEncoded()))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(published).isEmpty();
        }
    }

    @Test
    void nonAclEditPublishesWithoutAnAccessControlService() {
        InMemoryStorage storage =
                new InMemoryStorage(snapshot(serialize(new AccessControl()), Optional.empty()));
        List<OrionDocumentRevision> published = new ArrayList<>();
        try {
            OrionConfigurationEditor editor =
                    new OrionConfigurationEditor(
                            storage, testCipher(), testMaterial(), storage.events);
            OrionConfigurationModification modification =
                    editor.edit(storage.snapshot.revision(), ChangeMetadata.EMPTY);
            try (Subscription subscription = editor.subscribe(published::add)) {
                modification.update(document -> new OrionDocument(new OrionDocument.SystemConfiguration(
                        document.system().accessControl(), document.system().https(),
                        List.of(new ConfigurationSecret("credential", testEnvelope())),
                        document.system().proxies(), document.system().connections(),
                        document.system().oidcProviders()), document.organizations()));
                modification.apply();
                storage.awaitEvents();
                assertThat(published).containsExactly(storage.snapshot);
                assertThat(storage.snapshot.result()
                        .valueOrFailure("Cannot read configuration document")
                        .system().secrets()).extracting(ConfigurationSecret::id)
                        .containsExactly("credential");
                assertThat(modification.revision()).isEqualTo(storage.snapshot.revision());
                assertThatThrownBy(modification::document).isInstanceOf(IllegalStateException.class);
            } finally {
                modification.discard();
            }
        } finally {
            storage.events.onStop();
        }
    }

    @Test
    void editorSubscriptionDeliversOnlyItsStorageSnapshotsAndCanBeClosed() {
        try (ServiceFixture fixture = fixture(new AclFixture());
                ServiceFixture other = fixture(new AclFixture())) {
            List<OrionDocumentRevision> received = new ArrayList<>();
            Subscription subscription = fixture.editor.subscribe(received::add);
            try {
                OrionDocumentRevision published = fixture.storage.snapshot;
                fixture.storage.events.publishAndWait(new ConfigurationChanged(fixture.storage, published));
                assertThat(received).containsExactly(published);

                fixture.storage.events.publishAndWait(
                        new ConfigurationChanged(other.storage, other.storage.snapshot));
                assertThat(received).containsExactly(published);

                subscription.close();
                fixture.storage.events.publishAndWait(new ConfigurationChanged(fixture.storage, published));
                assertThat(received).containsExactly(published);
            } finally {
                subscription.close();
            }
        }
    }

    @Test
    void externalReloadActivatesRootCredentialsWithoutSavingAgain() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("root")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY,
                        "root-auth-generation:external", key(KEY_ONE.getPublic())));
        try (ServiceFixture fixture = fixture(initial)) {
            initial.getUsers().getFirst().addCredential(CredentialType.OPENSSH_PUBLIC_KEY,
                    "root-auth-generation:external", key(KEY_TWO.getPublic()));
            fixture.storage.snapshot =
                    new OrionDocumentRevision(
                            Result.of(OrionDocument.withAccessControl(initial.toAccessControl())),
                            new OrionRevision.Stored("external-root"));
            fixture.storage.publish("external root update");
            assertThat(fixture.storage.saveCount).isZero();
            assertThat(fixture.service.configuration().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl())
                    .isEqualTo(initial.toAccessControl());
            assertThat(
                            fixture.service.authenticateSshUser(
                                    "root",
                                    pro.deta.orion.util.KeyUtils.readPublicKeyFromString(
                                                    key(KEY_TWO.getPublic()))
                                            .getEncoded()))
                    .isInstanceOf(AuthenticationResult.Success.class);
            assertThat(fixture.service.configuration().revision())
                    .isEqualTo(fixture.storage.snapshot.revision());
        }
    }

    @Test
    void failedStartupDoesNotRetainPreparationOnRetry() {
        InMemoryStorage storage =
                new InMemoryStorage(snapshot(serialize(new AccessControl()), Optional.empty()));
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage,
                testCipher(), testMaterial(), storage.events);
        OrionAccessControlServiceImpl service =
                new OrionAccessControlServiceImpl(
                        new OrionPasswordHashingService(),
                        new RootPasswordReset(false),
                        testServerIdentity(), SIGNING,
                        configuration,
                        editor);
        try {
            storage.loadUnavailable = true;
            assertThatThrownBy(service::onStart).isInstanceOf(IllegalStateException.class);
            storage.loadUnavailable = false;
            service.onStart();
            new ServiceFixture(service, storage, editor).createOrUpdateUser(userUpdate("alice", "hash"));
            assertThat(service.userExists("alice")).isTrue();
            service.onStop();
            OrionDocumentRevision active = service.configuration();
            storage.snapshot = snapshot(serialize(new AccessControl()), Optional.of("after-stop"));
            storage.publish("after stopped startup retry");
            assertThat(service.configuration()).isSameAs(active);
        } finally {
            service.onStop();
            storage.events.onStop();
        }
    }

    @Test
    void reloadFailureAfterSaveConsumesTheEditWithoutClaimingRollback() {
        FailingReloadStorage storage = new FailingReloadStorage(
                snapshot(serialize(new AccessControl()), Optional.empty()));
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage, testCipher(), testMaterial(), new OrionEventManager());
        OrionConfigurationModification modification =
                editor.edit(new OrionRevision.Absent(), ChangeMetadata.EMPTY);
        try {
            modification.apply();
            assertThat(storage.saved).isTrue();
            assertThat(modification.revision()).isEqualTo(new OrionRevision.Stored("saved"));
            assertThat(storage.load().result()).isInstanceOf(Result.Failure.class);
            assertThatThrownBy(modification::apply).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Configuration modification is already finished");
        } finally {
            modification.discard();
        }
    }

    @Test
    void createsAUserInTheOrionDocumentRevision() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice"));

        try (ServiceFixture fixture = fixture(primary)) {
            assertThat(fixture.service.listSshCredentials("bob"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Failure.class,
                            failure -> assertThat(failure.code()).isEqualTo(SshCredentialFailureCode.USER_NOT_FOUND));
            fixture.createOrUpdateUser(userUpdate("bob", "new-password-hash"));
            assertThat(fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl().users())
                    .extracting(User::id).containsExactlyInAnyOrder("alice", "bob");
            assertThat(fixture.service.userExists("BOB")).isTrue();
            fixture.createOrUpdateUser(userUpdate("BOB", "replacement-password-hash"));
            assertThat(fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl().users())
                    .extracting(User::id).containsExactlyInAnyOrder("alice", "BOB");
        }
    }

    @Test
    void connectionSelectorNeverGrantsAdministrationInSystemOrOrganizationScope() {
        Grant mixed = new Grant("mixed", List.of(
                new GrantExpression(GrantKey.CONNECTION, "*"),
                new GrantExpression(GrantKey.ADMIN, "true")));
        User actor = new User("alice", "", "", "", List.of(), List.of(), List.of(mixed));
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
            assertThat(fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl().users())
                    .extracting(User::id).containsExactly("alice");
        }
    }

    @Test
    void configurationStatusTracksStoredValidationSeparatelyFromActiveRevision() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            OrionAccessControlServiceImpl.ConfigurationStatus initial = fixture.service.configurationStatus();
            assertThat(initial.storedRevision()).contains("version-one");
            assertThat(initial.activeRevision()).contains("version-one");
            assertThat(initial.validation()).isEqualTo("valid");

            fixture.storage.snapshot = snapshot(
                    serialize(invalidDocument()), Optional.of("version-two"));
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

            fixture.storage.snapshot = snapshot(serialize(new AccessControl()), Optional.of("version-three"));
            fixture.storage.publish("test recovery");
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
        OrganizationId organization = new OrganizationId("acme");
        String issuer = "https://login.example.test";
        User assigned = new User("alice", null, null, null,
                List.of(new Credential(CredentialType.OIDC_SUBJECT, issuer, "alice")),
                List.of("acme/reader"), List.of());
        try (ServiceFixture fixture = fixture(organizationTokenDocument(List.of(assigned)))) {
            OrionAccessControlServiceImpl service = fixture.service;
            Consumer<OrionDocument> publish = document -> {
                fixture.storage.save(fixture.storage.snapshot.revision(), document, ChangeMetadata.EMPTY);
                fixture.storage.awaitEvents();
            };
        TokenIssueResult issued = service.issueOrganizationToken(organization, "alice", issuer, "alice", 60);
        assertThat(issued).isInstanceOf(TokenIssueResult.Success.class);
        byte[] token = ((TokenIssueResult.Success) issued).token().getBytes(StandardCharsets.UTF_8);
        TokenAuthenticationResult authenticated = service.verifyToken(token);
        assertThat(authenticated).isInstanceOf(TokenAuthenticationResult.Success.class);
        SecurityContext context = SecurityContext.createContext().withUserIdentity(
                ((TokenAuthenticationResult.Success) authenticated).userIdentity());
        RepositoryResource repository = RepositoryResource.of("acme/team/repo");
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isTrue();
        User revoked = new User("alice", null, null, null,
                assigned.credentials(), List.of(), List.of());
        publish.accept(organizationTokenDocument(List.of(revoked)));
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isFalse();
        publish.accept(organizationTokenDocument(List.of(assigned)));
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isTrue();
        publish.accept(organizationTokenDocument(List.of()));
        assertThat(RepositoryAccessRules.read().evaluate(context, repository).allowed()).isFalse();
        assertThat(service.verifyToken(token)).isInstanceOf(TokenAuthenticationResult.Failure.class);
        }
    }

    private static OrionDocument organizationTokenDocument(List<User> users) {
        ScopedGrant grant = new ScopedGrant(new GrantId("read"), ScopedGrant.Effect.ALLOW,
                List.of(new GrantExpression(GrantKey.READ, "true")));
        ScopedRole role = new ScopedRole(new RoleId("reader"), List.of(),
                List.of(GrantAddress.parse("acme/read")));
        OrionDocument.Repository repository = new OrionDocument.Repository("repo", "",
                OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), List.of(), java.util.Optional.empty());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                List.of(repository));
        OidcProvider provider = new OidcProvider("oidc", URI.create("https://login.example.test"), "client",
                "secret", OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0);
        OrionDocument.Organization organization =
                new OrionDocument.Organization(
                        new OrganizationId("acme"),
                        "",
                        users,
                        List.of(grant),
                        List.of(role),
                        List.of(team),
                        List.of(new ConfigurationSecret("secret", testEnvelope())),
                        List.of(provider),
                        List.of(),
                        List.of());
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
        alice.addCredential(CredentialType.SHA1, passwordHash);
        alice.addRole("operators");
        primary.getUsers().add(alice);
        AclFixture.User bob = user("bob");
        bob.addCredential(CredentialType.SHA1, passwordHash);
        primary.getUsers().add(bob);
        AclFixture.Role role = new AclFixture.Role();
        role.setId("operators");
        AclFixture.Grant grant = new AclFixture.Grant();
        grant.setId("operator-rights");
        grant.addKey(GrantKey.READ, "team/*");
        Grant originalGrant = grant.toAccessControl();
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
            grant.addKey(GrantKey.ADMIN, "true");
            duringPasswordCheck.set(() -> {
                fixture.storage.snapshot = snapshot(
                        serialize(primary.toAccessControl()), Optional.of("version-two"));
                fixture.storage.publish("replace operators membership");
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
            OrionConfigurationModification appliedEdit =
                    fixture.editor
                            .edit(
                                    new OrionRevision.Stored("version-one"),
                                    new ChangeMetadata(UserEmail.EMPTY, "update proxy"))
                            .update(
                                    document ->
                                            new OrionDocument(
                                                    new OrionDocument.SystemConfiguration(
                                                            document.system().accessControl(),
                                                            document.system().https(),
                                                            List.of(
                                                                    new ConfigurationSecret(
                                                                            "credential",
                                                                            testEnvelope())),
                                                            document.system().proxies(),
                                                            document.system().connections(),
                                                            document.system().oidcProviders()),
                                                    document.organizations()));
            appliedEdit.apply();
            OrionDocumentRevision result = fixture.storage.snapshot;

            assertThat(result.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().secrets()).extracting("id").containsExactly("credential");
            assertThat(fixture.storage.snapshot.revision()).isEqualTo(appliedEdit.revision());
        }
    }

    @Test
    void rejectsStalePrimaryConfigurationBeforeInvokingTheMutation() {
        try (var fixture = fixture(new AclFixture())) {
            int saves = fixture.storage.saveCount;
            assertThatThrownBy(
                            () ->
                                    fixture.editor
                                            .edit(
                                                    new OrionRevision.Stored("stale"),
                                                    new ChangeMetadata(
                                                            UserEmail.EMPTY, "update proxy"))
                                            .update(
                                                    document -> {
                                                        throw new AssertionError(
                                                                "A stale mutation must not consume credentials");
                                                    })
                                            .apply())
                    .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            assertThat(fixture.storage.saveCount).isEqualTo(saves);
        }
    }

    @Test
    void reportsAStaleRevisionEvenWhenTheNewHeadIsInvalid() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            fixture.storage.snapshot = snapshot(serialize(invalidDocument()), Optional.of("version-two"));
            assertThatThrownBy(
                            () ->
                                    fixture.editor
                                            .edit(
                                                    new OrionRevision.Stored("version-one"),
                                                    new ChangeMetadata(
                                                            UserEmail.EMPTY, "update ACL"))
                                            .update(
                                                    document -> {
                                                        throw new AssertionError(
                                                                "A stale mutation must not parse or change the new head");
                                                    })
                                            .apply())
                    .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            assertThat(fixture.storage.saveCount).isZero();
        }
    }

    @Test
    void publishesAndPreservesTheWholeDesiredStateWhenAclChanges() throws Exception {
        OrionHttpsConfiguration https =
                new OrionHttpsConfiguration(
                        true,
                        "localhost",
                        8443,
                        URI.create("https://localhost:8443"),
                        Optional.of(new OrionMaterialReference("https-identity", 1)),
                        Optional.empty(),
                        OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                        List.of(),
                        Optional.empty());
        OrionDocument initial = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.of(https), List.of(), List.of(), List.of(), List.of()), List.of());
        try (ServiceFixture fixture = fixture(initial)) {
            assertThat(fixture.service.configuration().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().https()).contains(https);
            fixture.createOrUpdateUser(userUpdate("alice", "password-hash"));
            assertThat(fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().https()).contains(https);
            assertThat(fixture.service.configuration().result()
                    .valueOrFailure("Cannot read configuration document"))
                    .isEqualTo(fixture.storage.snapshot.result()
                            .valueOrFailure("Cannot read configuration document"));
            OrionDocumentRevision lastValid = fixture.service.configuration();
            fixture.storage.snapshot = new OrionDocumentRevision(Result.of(invalidDocument()),
                    new OrionRevision.Stored("broken-version"));
            fixture.storage.publish("invalid configuration");
            assertThat(fixture.service.configuration()).isSameAs(lastValid);
            assertThat(fixture.service.configurationStatus().validation()).isEqualTo("invalid");
        }
    }

    @Test
    void missingHttpsMaterialRetainsTheLastPublishedDocumentAndAcl() throws Exception {
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        try (ServiceFixture fixture = fixture(initial, testServerIdentity(), new OrionPasswordHashingService(),
                OrionKeyMaterial.unavailable())) {
            OrionDocumentRevision lastValid = fixture.service.configuration();
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(true, "localhost", 8443,
                    URI.create("https://localhost:8443"),
                    Optional.of(new OrionMaterialReference("missing-identity", 1)), Optional.empty(),
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.empty());
            OrionDocument candidate =
                    new OrionDocument(
                            new OrionDocument.SystemConfiguration(
                                    initial.system().accessControl(),
                                    Optional.of(https),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.of()),
                            List.of());
            fixture.storage.snapshot =
                    new OrionDocumentRevision(
                            Result.of(candidate), new OrionRevision.Stored("invalid-commit"));
            fixture.storage.publish("missing material");
            assertThat(fixture.service.configuration()).isSameAs(lastValid);
            assertThat(fixture.service.isRunning()).isTrue();
            assertThat(fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")).isEqualTo(candidate);
            assertThat(fixture.service.configurationStatus().storedRevision()).contains("invalid-commit");
            assertThat(fixture.service.configurationStatus().validation()).isEqualTo("invalid");
            assertFailure(
                    fixture.service.listSshCredentials("missing-user"),
                    SshCredentialFailureCode.USER_NOT_FOUND);
        }
    }

    @Test
    void changedConfigurationPublishesItsRevisionWithoutReloadingUnchangedAcl() throws Exception {
        AccessControl acl = new AccessControl();
        try (ServiceFixture fixture = fixture(OrionDocument.withAccessControl(acl))) {
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 8443,
                    URI.create("https://localhost:8443"), Optional.empty(), Optional.empty(),
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.empty());
            OrionDocument changed = new OrionDocument(new OrionDocument.SystemConfiguration(
                    acl, Optional.of(https), List.of(), List.of(), List.of(), List.of()), List.of());
            fixture.storage.snapshot =
                    new OrionDocumentRevision(Result.of(changed), new OrionRevision.Stored("second-commit"));
            fixture.storage.publish("configuration changed");
            assertThat(fixture.service.configuration().revision())
                    .isEqualTo(new OrionRevision.Stored("second-commit"));
            assertThat(fixture.service.configuration().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().https()).contains(https);
            assertThat(fixture.service.configuration().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl()).isSameAs(acl);
        }
    }

    @Test
    void invalidSecretEnvelopeRetainsTheLastPublishedSnapshot() throws Exception {
        try (ServiceFixture fixture = fixture(OrionDocument.withAccessControl(new AccessControl()))) {
            OrionDocumentRevision lastValid = fixture.service.configuration();
            fixture.storage.snapshot =
                    new OrionDocumentRevision(
                            Result.of(invalidDocument()), new OrionRevision.Stored("invalid"));
            fixture.storage.publish("invalid secret");
            assertThat(fixture.service.configuration()).isSameAs(lastValid);
        }
    }

    @Test
    void listsCanonicalDeduplicatedSshCredentialsForOnlyTheSelectedUser() {
        AclFixture primary = new AclFixture();
        AclFixture.User alice = user("alice")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic()))
                .addCredential(CredentialType.ARGON2, "password-hash")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()));
        primary.getUsers().add(alice);
        primary.getUsers().add(user("bob")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_THREE.getPublic())));

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
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, "not-a-key"));

        try (ServiceFixture fixture = fixture(primary)) {
            assertFailure(
                    fixture.service.listSshCredentials("alice"),
                    SshCredentialFailureCode.INVALID_STORED_KEY);
        }
    }

    @Test
    void atomicallyAddsCanonicalKeysToTheOrionDocumentRevision() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(CredentialType.ARGON2, "password-hash"));
        primary.getUsers().add(user("bob")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_THREE.getPublic())));

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
                CredentialType.OPENSSH_PUBLIC_KEY,
                "root-auth-generation:generation-one",
                key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            assertThat(fixture.addSshCredentials("root", List.of(key(KEY_TWO.getPublic()))))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);

            assertThat(credentials(fixture.storage.snapshot, "root"))
                    .filteredOn(credential -> credential.type() == CredentialType.OPENSSH_PUBLIC_KEY)
                    .extracting(Credential::keyId)
                    .containsOnly("root-auth-generation:generation-one");
        }
    }

    @Test
    void removesEveryDuplicateOfAUniqueKeyWithoutTouchingOtherCredentialsOrUsers() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic()))
                .addCredential(CredentialType.ARGON2, "password-hash"));
        primary.getUsers().add(user("bob")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

        try (ServiceFixture fixture = fixture(primary)) {
            SshCredentialUpdateResult removed = fixture.removeSshCredential(
                    "alice",
                    descriptor(KEY_ONE.getPublic()).fingerprint(),
                    false);

            assertThat(removed).isInstanceOfSatisfying(
                    SshCredentialUpdateResult.Success.class,
                    success -> assertThat(success.credentials()).containsExactly(descriptor(KEY_TWO.getPublic())));
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .filteredOn(credential -> credential.type() == CredentialType.ARGON2)
                    .singleElement()
                    .extracting(Credential::value)
                    .isEqualTo("password-hash");
            assertThat(sshValues(fixture.storage.snapshot, "bob"))
                    .containsExactly(key(KEY_ONE.getPublic()));
        }
    }

    @Test
    void removalRejectsMissingAmbiguousMalformedAndUnforcedLastKeyWithoutSaving() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic()))
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_TWO.getPublic())));

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
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, "not-a-key"));
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
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

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
                        CredentialType.OPENSSH_PUBLIC_KEY,
                        "root-auth-generation:" + generation,
                        key(KEY_ONE.getPublic()))
                .addCredential(
                        CredentialType.OPENSSH_PUBLIC_KEY,
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
                    .extracting(Credential::keyId)
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
                    .extracting(Credential::keyId)
                    .asString()
                    .startsWith("root-auth-locked:");
        }
    }

    @Test
    void tokenAuthenticationReturnsValidatedTokenIdentity() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

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
                        CredentialType.ARGON2,
                        "root-auth-locked:generation",
                        "locked-hash")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        primary.getUsers().add(user("alice")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));

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
    void adminUserUpdateWritesOnlyTheOrionDocumentRevision() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        primary.getUsers().add(user("alice")
                .addCredential(CredentialType.ARGON2, "old-hash"));

        try (ServiceFixture fixture = fixture(primary)) {
            fixture.createOrUpdateUser(userUpdate("alice", "new-hash"));

            assertThat(fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl().users())
                    .extracting(User::id).containsExactlyInAnyOrder("root", "alice");
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .singleElement()
                    .extracting(Credential::value)
                    .isEqualTo("new-hash");
        }
    }

    @Test
    void staleAdminEditCannotResurrectARootKeyAndCanRetryAtTheWinningRevision() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        primary.getUsers().add(user("alice")
                .addCredential(CredentialType.ARGON2, "old-hash"));

        try (ServiceFixture fixture = fixture(primary)) {
            OrionRevision revision = fixture.service.configuration().revision();
            AccessControlAccess admin = fixture.service.createAccess(revision,
                    new ChangeMetadata(UserEmail.EMPTY, "update alice"));
            AccessControlAccess removal = fixture.service.createAccess(revision,
                    new ChangeMetadata(UserEmail.EMPTY, "remove root key"));
            try {
                admin.createOrUpdateUser(userUpdate("alice", "new-hash"));
                assertThat(removal.removeSshCredential("root",
                        descriptor(KEY_ONE.getPublic()).fingerprint(), true))
                        .isInstanceOf(SshCredentialUpdateResult.Success.class);
                removal.apply();
                fixture.storage.awaitEvents();
                OrionDocumentRevision winner = fixture.service.configuration();

                assertThatThrownBy(admin::apply)
                        .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
                fixture.storage.awaitEvents();
                assertThat(fixture.storage.saveCount).isEqualTo(1);
                assertThat(fixture.service.configuration()).isEqualTo(winner);
                assertThat(sshValues(fixture.storage.snapshot, "root")).isEmpty();
                assertThat(credentials(fixture.storage.snapshot, "alice"))
                        .singleElement().extracting(Credential::value).isEqualTo("old-hash");

                fixture.createOrUpdateUser(userUpdate("alice", "new-hash"));
                assertThat(fixture.storage.saveCount).isEqualTo(2);
                assertThat(sshValues(fixture.storage.snapshot, "root")).isEmpty();
                assertThat(credentials(fixture.storage.snapshot, "root"))
                        .extracting(Credential::keyId)
                        .singleElement().asString().startsWith("root-auth-locked:");
                assertThat(credentials(fixture.storage.snapshot, "alice"))
                        .singleElement().extracting(Credential::value).isEqualTo("new-hash");
                assertThat(fixture.service.authenticateSshUser("root", KEY_ONE.getPublic().getEncoded()))
                        .isInstanceOf(AuthenticationResult.Failure.class);
            } finally {
                admin.discard();
                removal.discard();
            }
        }
    }

    @Test
    void rootCredentialUpdatePreservesOtherUsers() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("alice").addCredential(CredentialType.ARGON2, "alice-hash"));
        primary.getUsers().add(user("root")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        try (ServiceFixture fixture = fixture(primary, testServerIdentity())) {
            fixture.addSshCredentials("root", List.of(key(KEY_TWO.getPublic())));
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .singleElement().extracting(Credential::value).isEqualTo("alice-hash");
            assertThat(fixture.service.listSshCredentials("root"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Success.class, success ->
                            assertThat(success.credentials()).containsExactlyInAnyOrder(
                                    descriptor(KEY_ONE.getPublic()), descriptor(KEY_TWO.getPublic())));
        }
    }

    @Test
    void credentialUpdateActivatesThePublishedSnapshotWithoutReloading() {
        AclFixture primary = new AclFixture();
        primary.getUsers().add(user("root")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        try (ServiceFixture fixture = fixture(primary)) {
            int loads = fixture.storage.loadCount;
            fixture.addSshCredentials("root", List.of(key(KEY_TWO.getPublic())));
            assertThat(fixture.storage.saveCount).isEqualTo(1);
            assertThat(fixture.storage.loadCount).isEqualTo(loads + 1);
            assertThat(fixture.service.configuration().revision())
                    .isEqualTo(fixture.storage.snapshot.revision());
            assertThat(fixture.service.listSshCredentials("root"))
                    .isInstanceOfSatisfying(SshCredentialListResult.Success.class, success ->
                            assertThat(success.credentials()).contains(descriptor(KEY_TWO.getPublic())));
        }
    }

    @Test
    void credentialUpdateCanRetryAgainstConcurrentConfiguration() {
        AclFixture initial = new AclFixture();
        initial.getUsers().add(user("root")
                .addCredential(CredentialType.OPENSSH_PUBLIC_KEY, key(KEY_ONE.getPublic())));
        try (ServiceFixture fixture = fixture(initial)) {
            initial.getUsers().add(user("alice").addCredential(CredentialType.ARGON2, "alice-hash"));
            fixture.storage.concurrentReplacement = snapshot(serialize(initial.toAccessControl()),
                    Optional.of("winning-revision"));
            assertFailure(fixture.addSshCredentials("root", List.of(key(KEY_TWO.getPublic()))),
                    SshCredentialFailureCode.CONCURRENT_UPDATE);
            fixture.storage.awaitEvents();
            assertThat(fixture.service.configuration().revision())
                    .isEqualTo(new OrionRevision.Stored("winning-revision"));
            assertThat(fixture.addSshCredentials("root", List.of(key(KEY_TWO.getPublic()))))
                    .isInstanceOf(SshCredentialUpdateResult.Success.class);
            assertThat(credentials(fixture.storage.snapshot, "alice"))
                    .singleElement().extracting(Credential::value).isEqualTo("alice-hash");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsDefaultOrganizationOnlyForNewConfigurationAndPreservesItOnRestart(boolean resetRoot)
            throws Exception {
        InMemoryStorage storage = new InMemoryStorage(null);
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(true);
        OrionConfigurationEditor editor = new OrionConfigurationEditor(storage,
                testCipher(), testMaterial(), storage.events);
        OrionAccessControlServiceImpl service =
                new OrionAccessControlServiceImpl(
                        new OrionPasswordHashingService(),
                        new RootPasswordReset(resetRoot),
                        testServerIdentity(), SIGNING,
                        configuration,
                        editor);
        PrintStream originalOut = System.out;
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(output);
            service.onStart();
            OrionDocument created = storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document");
            assertThat(created.organizations()).extracting(org -> org.id().value()).containsExactly("default");
            assertThat(created.organizations().getFirst().users()).isEmpty();
            assertThat(created.system().accessControl().users()).extracting(User::id).contains("root");
            service.onStop();
            OrionDocumentRevision beforeRestart = storage.snapshot;
            OrionAccessControlServiceImpl restarted =
                    new OrionAccessControlServiceImpl(
                            new OrionPasswordHashingService(),
                            new RootPasswordReset(false),
                            testServerIdentity(), SIGNING,
                            configuration,
                            editor);
            try {
                restarted.onStart();
                assertThat(storage.snapshot).isSameAs(beforeRestart);
                assertThat(restarted.configuration().result()
                        .valueOrFailure("Cannot read configuration document")
                        .organizations())
                        .isEqualTo(created.organizations());
            } finally {
                restarted.onStop();
            }
        } finally {
            System.setOut(originalOut);
            service.onStop();
            storage.events.onStop();
        }
    }

    @Test
    void doesNotInsertDefaultOrganizationIntoExistingConfiguration() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            assertThat(fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .organizations()).isEmpty();
        }
    }

    @Test
    void resetFailsWithoutPrintingWhenPersistenceFails() throws Exception {
        assertRecoveryFailsWithoutPrinting(defaultAclSnapshot(), new RootPasswordReset(true));
    }

    @Test
    void defaultCreationFailsWithoutPrintingWhenPersistenceFails() throws Exception {
        assertRecoveryFailsWithoutPrinting(null, new RootPasswordReset(false));
    }

    private static void assertRecoveryFailsWithoutPrinting(
            OrionDocumentRevision initial, RootPasswordReset rootPasswordReset) {
        FailingReloadStorage storage = new FailingReloadStorage(initial);
        storage.failSave = true;
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(true);
        OrionEventManager events = new OrionEventManager();
        events.onStart();
        OrionAccessControlServiceImpl service =
                new OrionAccessControlServiceImpl(
                        new OrionPasswordHashingService(),
                        rootPasswordReset,
                        testServerIdentity(), SIGNING,
                        configuration,
                        new OrionConfigurationEditor(
                                storage, testCipher(), testMaterial(), events));
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream output = new PrintStream(processOutput, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            assertThatThrownBy(service::onStart).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Configuration repository not initialized");
        } finally {
            System.setOut(originalOut);
            service.onStop();
            events.onStop();
        }
        assertThat(storage.saved).isTrue();
        assertThat(processOutput.toString(StandardCharsets.UTF_8)).doesNotContain("---ROOT PASSWORD: ");
    }

    private static OrionDocumentRevision defaultAclSnapshot() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(OrionDocument.withAccessControl(
                DefaultAccessControl.create("old-password-hash")), output);
        return snapshot(output.toByteArray(), Optional.of("initial"));
    }

    @Test
    void userUpdatePersistsReadWriteGrantWithoutSeparateReadFlag() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            fixture.createOrUpdateUser(new AccessControlUserUpdate(
                    "alice", "alice@example.test", List.of(),
                    List.of(new AccessControlRepositoryGrantUpdate("project", false, true, false, false, "dev"))));

            AccessControl persisted = fixture.storage.snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl();
            User alice = persisted.users().stream()
                    .filter(user -> user.id().equals("alice")).findFirst().orElseThrow();
            assertThat(alice.grants().getFirst().info())
                    .extracting(GrantExpression::key)
                    .contains(GrantKey.READ_WRITE);
        }
    }

    @Test
    void reportsInvalidUserInputWithoutMutatingStorage() {
        try (ServiceFixture fixture = fixture(new AclFixture())) {
            OrionDocumentRevision original = fixture.storage.snapshot;
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
            OrionKeyMaterial serverIdentity) {
        return fixture(draft, serverIdentity, new OrionPasswordHashingService());
    }

    private static ServiceFixture fixture(
            AclFixture draft, OrionKeyMaterial serverIdentity, OrionPasswordHashingService hashing) {
        return fixture(OrionDocument.withAccessControl(draft.toAccessControl()), serverIdentity, hashing,
                testMaterial());
    }

    private static ServiceFixture fixture(OrionDocument document) {
        return fixture(document, testServerIdentity(), new OrionPasswordHashingService(), testMaterial());
    }

    private static ServiceFixture fixture(OrionDocument document, OrionKeyMaterial identity,
            OrionPasswordHashingService hashing, OrionKeyMaterial material) {
        InMemoryStorage storage = new InMemoryStorage(
                new OrionDocumentRevision(Result.of(document), new OrionRevision.Stored("version-one")));
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        OrionConfigurationEditor editor =
                new OrionConfigurationEditor(storage, testCipher(), material, storage.events);
        OrionAccessControlServiceImpl service = new OrionAccessControlServiceImpl(hashing,
                new RootPasswordReset(false), identity, SIGNING, configuration, editor);
        try {
            service.onStart();
            return new ServiceFixture(service, storage, editor);
        } catch (RuntimeException failure) {
            service.onStop();
            storage.events.onStop();
            throw failure;
        }
    }

    private static OrionDocumentRevision snapshot(byte[] bytes, Optional<String> revision) {
        return new OrionDocumentRevision(Result.of(parseDocument(bytes)),
                revision.<OrionRevision>map(OrionRevision.Stored::new)
                        .orElseGet(OrionRevision.Absent::new));
    }

    private static OrionDocument invalidDocument() {
        return new OrionDocument(
                new OrionDocument.SystemConfiguration(
                        new AccessControl(),
                        Optional.empty(),
                        List.of(new ConfigurationSecret("invalid", "not-an-envelope")),
                        List.of(),
                        List.of(),
                        List.of()),
                List.of());
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
            List<pro.deta.orion.schema.acl.User> immutableUsers = new ArrayList<>();
            for (User user : users) immutableUsers.add(user.toAccessControl());
            List<pro.deta.orion.schema.acl.Role> immutableRoles = new ArrayList<>();
            for (Role role : roles) immutableRoles.add(role.toAccessControl());
            List<pro.deta.orion.schema.acl.Grant> immutableGrants = new ArrayList<>();
            for (Grant grant : grants) immutableGrants.add(grant.toAccessControl());
            return new AccessControl(immutableUsers, immutableRoles, immutableGrants);
        }

        private static final class User {
            private String id;
            private String email;
            private final List<Credential> credentials = new ArrayList<>();
            private final List<String> roles = new ArrayList<>();

            void setId(String id) { this.id = id; }
            void setEmail(String email) { this.email = email; }
            List<String> getRoles() { return roles; }

            User addCredential(CredentialType type, String value) {
                return addCredential(type, null, value);
            }

            User addCredential(CredentialType type, String keyId, String value) {
                credentials.add(new Credential(type, keyId, value));
                return this;
            }

            User addRole(String role) {
                roles.add(role);
                return this;
            }

            pro.deta.orion.schema.acl.User toAccessControl() {
                return new pro.deta.orion.schema.acl.User(id, null, null, email, credentials, roles, List.of());
            }
        }

        private static final class Role {
            private String id;
            private final List<Grant> grants = new ArrayList<>();
            private final List<String> references = new ArrayList<>();

            void setId(String id) { this.id = id; }
            void addGrant(Grant grant) { grants.add(grant); }
            void addGrantReference(String id) { references.add(id); }

            pro.deta.orion.schema.acl.Role toAccessControl() {
                List<pro.deta.orion.schema.acl.Grant> immutableGrants = new ArrayList<>();
                for (Grant grant : grants) immutableGrants.add(grant.toAccessControl());
                return new pro.deta.orion.schema.acl.Role(id, immutableGrants, references);
            }
        }

        private static final class Grant {
            private String id;
            private final List<GrantExpression> info = new ArrayList<>();

            void setId(String id) { this.id = id; }
            String getId() { return id; }
            List<GrantExpression> getInfo() { return info; }

            Grant addKey(GrantKey key, String value) {
                info.add(new GrantExpression(key, value));
                return this;
            }

            pro.deta.orion.schema.acl.Grant toAccessControl() {
                return new pro.deta.orion.schema.acl.Grant(id, info);
            }
        }
    }

    private static AccessControlUserUpdate userUpdate(String id, String passwordHash) {
        return new AccessControlUserUpdate(
                id,
                id + "@updated.example.test",
                List.of(new AccessControlCredentialUpdate(CredentialType.ARGON2, passwordHash)),
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

    private static List<String> sshValues(OrionDocumentRevision file, String userId) {
        for (User user : file.result()
                .valueOrFailure("Cannot read configuration document")
                .system().accessControl().users()) {
            if (userId.equalsIgnoreCase(user.id())) {
                return user.credentials().stream()
                        .filter(credential -> credential.type() == CredentialType.OPENSSH_PUBLIC_KEY)
                        .map(Credential::value)
                        .toList();
            }
        }
        return List.of();
    }

    private static List<Credential> credentials(
            OrionDocumentRevision file,
            String userId) {
        for (User user : file.result()
                .valueOrFailure("Cannot read configuration document")
                .system().accessControl().users()) {
            if (userId.equalsIgnoreCase(user.id())) {
                return user.credentials();
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

    private static String testEnvelope() {
        return testEnvelope(3);
    }

    private static String testEnvelope(int marker) {
        try {
            return new ConfigurationSecretEnvelopeCodec()
                    .serialize(
                            new ConfigurationSecretEnvelope(
                                    1,
                                    "configuration-v1",
                                    1,
                                    ConfigurationSecretEnvelopeCodec.AES_WRAP,
                                    ConfigurationSecretEnvelopeCodec.AES_GCM,
                                    ConfigurationSecretEnvelopeCodec.BASE64_URL,
                                    new byte[] {1},
                                    new byte[] {2},
                                    new byte[] {(byte) marker}));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ConfigurationSecretCipher testCipher() {
        return new ConfigurationSecretCipher() {
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

    private static OrionKeyMaterial testMaterial() {
        return new OrionKeyMaterial() {
            @Override
            public void require(KeyMaterialDescriptor descriptor) {
            }

            @Override
            public void require(TrustedCertificateDescriptor descriptor) {
            }
        };
    }

    private static OrionKeyMaterial testServerIdentity() {
        return new OrionKeyMaterial() {
            @Override
            public byte[] sign(KeyMaterialDescriptor descriptor, byte[] payload)
                    throws java.security.GeneralSecurityException {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(KEY_THREE.getPrivate());
                signature.update(payload);
                return signature.sign();
            }

            @Override
            public boolean verify(KeyMaterialDescriptor descriptor, byte[] payload, byte[] signatureBytes)
                    throws java.security.GeneralSecurityException {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initVerify(KEY_THREE.getPublic());
                signature.update(payload);
                return signature.verify(signatureBytes);
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
        private <T> T withAccess(OrionConfigurationModification modification,
                java.util.function.Function<AccessControlAccess, T> operation) {
            AccessControlAccess access =
                    new AccessControlAccess(modification, new OrionPasswordHashingService());
            try {
                T result = operation.apply(access);
                storage.awaitEvents();
                return result;
            } finally {
                access.discard();
            }
        }

        private void createOrUpdateUser(AccessControlUserUpdate update) {
            withAccess(editor.edit(storage.snapshot.revision(), new ChangeMetadata(
                    new UserEmail(update.id(), update.email()), "update user " + update.id())), access -> {
                access.createOrUpdateUser(update);
                access.apply();
                return null;
            });
        }

        private SshCredentialUpdateResult addSshCredentials(String userId, List<String> keys) {
            return mutate(userId, "add SSH credentials", access -> access.addSshCredentials(userId, keys));
        }

        private SshCredentialUpdateResult removeSshCredential(String userId, String prefix, boolean force) {
            return mutate(userId, "remove SSH credential",
                    access -> access.removeSshCredential(userId, prefix, force));
        }

        private SshCredentialUpdateResult mutate(String userId, String message,
                java.util.function.Function<AccessControlAccess, SshCredentialUpdateResult> mutation) {
            try {
                return withAccess(editor.edit(storage.snapshot.revision(), new ChangeMetadata(
                        new UserEmail(userId, ""), message + " for " + userId)), access -> {
                    SshCredentialUpdateResult result = mutation.apply(access);
                    if (result instanceof SshCredentialUpdateResult.Success success && success.changed()) {
                        access.apply();
                    }
                    return result;
                });
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
            storage.events.onStop();
        }
    }

    private static final class InMemoryStorage implements OrionVersionedConfigurationStorage {
        private final OrionEventManager events = new OrionEventManager();
        private OrionDocumentRevision concurrentReplacement;
        private volatile OrionDocumentRevision snapshot;
        private int saveCount;
        private int loadCount;
        private boolean concurrentOnSave;
        private boolean loadUnavailable;

        private InMemoryStorage(OrionDocumentRevision snapshot) {
            this.snapshot = snapshot;
            events.onStart();
        }

        @Override
        public OrionDocumentRevision load() {
            loadCount++;
            if (loadUnavailable)
                return new OrionDocumentRevision(
                        new Result.Failure<>(Result.FailureCode.GENERAL),
                        new OrionRevision.Absent());
            return snapshot == null
                    ? new OrionDocumentRevision(
                            new Result.Failure<>(Result.FailureCode.NOT_FOUND),
                            new OrionRevision.Absent())
                    : snapshot;
        }

        @Override
        public OrionDocumentRevision load(OrionRevision.Stored revision) {
            if (snapshot != null && snapshot.revision().equals(revision)) return load();
            return new OrionDocumentRevision(
                    new Result.Failure<>(Result.FailureCode.NOT_FOUND), new OrionRevision.Absent());
        }

        @Override
        public OrionRevision.Stored save(
                OrionRevision expectedRevision, OrionDocument document, ChangeMetadata metadata) {
            if (concurrentReplacement != null) {
                snapshot = concurrentReplacement;
                concurrentReplacement = null;
                events.publish(new ConfigurationChanged(this, snapshot));
                throw new OrionConfigurationConcurrentUpdateException("simulated winning revision", null);
            }
            if (concurrentOnSave || snapshot != null && !snapshot.revision().equals(expectedRevision)) {
                throw new OrionConfigurationConcurrentUpdateException("simulated race", null);
            }
            OrionRevision.Stored revision = new OrionRevision.Stored("saved-" + ++saveCount);
            snapshot = new OrionDocumentRevision(Result.of(document), revision);
            events.publish(new ConfigurationChanged(this, snapshot));
            return revision;
        }

        private void publish(String reason) {
            events.publishAndWait(new ConfigurationChanged(this, snapshot));
        }

        private void awaitEvents() {
            events.publishAndWait(new OrionEvent() {});
        }
    }

    private static final class FailingReloadStorage implements OrionVersionedConfigurationStorage {
        private final OrionDocumentRevision initial;
        private boolean saved;
        private boolean failSave;

        private FailingReloadStorage(OrionDocumentRevision initial) {
            this.initial = initial;
        }

        @Override
        public OrionDocumentRevision load() {
            if (saved)
                return new OrionDocumentRevision(
                        new Result.Failure<>(
                                Result.FailureCode.GENERAL,
                                "simulated reload failure",
                                new IOException("simulated reload failure")),
                        new OrionRevision.Absent());
            return initial == null
                    ? new OrionDocumentRevision(
                            new Result.Failure<>(Result.FailureCode.NOT_FOUND),
                            new OrionRevision.Absent())
                    : initial;
        }

        @Override
        public OrionDocumentRevision load(OrionRevision.Stored revision) {
            return load();
        }

        @Override
        public OrionRevision.Stored save(
                OrionRevision expectedRevision, OrionDocument document, ChangeMetadata metadata) {
            saved = true;
            if (failSave) throw new IllegalStateException("simulated save failure");
            return new OrionRevision.Stored("saved");
        }
    }
}
