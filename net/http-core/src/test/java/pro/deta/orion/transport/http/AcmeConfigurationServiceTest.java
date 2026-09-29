package pro.deta.orion.transport.http;

import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import pro.deta.orion.schema.orion.OrionMaterialReference;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.schema.orion.OrionAcmeConfiguration;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.util.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeConfigurationServiceTest {
    private static final String EAB_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void selectsStoredAccountKeyPersistsItsReferenceAndReusesItForIssuance() throws Exception {
        OrionDesiredState desired = new OrionDesiredState();
        MemoryStorage storage = new MemoryStorage();
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            owner.administration().create("existing-account", KeyMaterialPurpose.ACME_ACCOUNT, null);
            OrionMaterialReference reference = new OrionMaterialReference("existing-account", 1);
            OrionConfiguration bootstrap = new OrionConfiguration();
            bootstrap.getBootstrap().getKeyMaterial().setClusterId("test");
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.current().document(), owner.configurationCipher());
            OrionAccessControlServiceImpl acl = new OrionAccessControlServiceImpl(storage,
                    new OrionPasswordHashingService(), OrionRuntimeOptions.defaults(),
                    owner.serverIdentity(), desired, bootstrap, owner.configurationCipher(),
                    owner.configurationMaterial(), Optional.empty());
            acl.reload("test");
            List<KeyPair> issuedKeys = new ArrayList<>();
            AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(new AcmeHttpChallengeService()) {
                @Override
                public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
                    issuedKeys.add(request.accountKeyPair());
                    try {
                        return new IssuedAcmeCertificate(request.domains(), List.of(
                                TestCertificateChain.selfSignedLeaf("example.test", request.domainKeyPair())));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            };
            AcmeCertificateService certificates = new AcmeCertificateService(
                    bootstrap, desired, owner.acme(), issuer, secrets);
            AcmeConfigurationService service = new AcmeConfigurationService(desired, secrets, acl,
                    certificates, owner.configurationMaterial(), bootstrap);
            AcmeConfigurationService.View view = service.save(new AcmeConfigurationService.Settings("r1",
                    "letsencrypt", "", "admin@example.test", List.of("example.test"), "", null, reference), "root");
            assertThat(view.accountMaterial()).isEqualTo(reference);
            assertThat(OrionXml.read(new java.io.ByteArrayInputStream(storage.snapshot.files().get("orion.xml")))
                    .system().https().orElseThrow().acme().orElseThrow().accountMaterial()).contains(reference);
            acl.reload("restart");
            assertThat(service.view().accountMaterial()).isEqualTo(reference);
            DefaultCommandDispatcher dispatcher = AcmeAdministrationTest.dispatcher(
                    new AcmeCommandCatalog(service, certificates));
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request(
                    "/acme configure revision=r2 provider=letsencrypt email=admin@example.test "
                            + "domains=example.test account-key=existing-account account-key-version=1",
                    AcmeAdministrationTest.admin())))
                    .isInstanceOfSatisfying(CommandResult.ObjectValue.class, result ->
                            assertThat(result.fields().get("accountKey").asText()).isEqualTo("existing-account"));
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request(
                    "/acme configure account-key=existing-account", AcmeAdministrationTest.admin())))
                    .isInstanceOf(CommandResult.Failure.class);
            OrionAdminAcmeConfigurationRoute route = new OrionAdminAcmeConfigurationRoute(service, new ObjectMapper());
            assertThat(route.doPost(AcmeAdministrationTest.httpRequest("POST", "/api/admin/acme/configuration", """
                    {"revision":"r2","provider":"letsencrypt","accountEmail":"admin@example.test",
                     "domains":["example.test"],"accountMaterial":{"alias":"existing-account","version":1}}
                    """, AcmeAdministrationTest.admin())).status()).isEqualTo(200);
            certificates.issue(new AcmeCertificateService.IssueRequest(null, null, null, null, null, null, true));
            assertThat(issuedKeys).hasSize(1);
            String expected = owner.configurationMaterial().inventory().getFirst().publicKeyPem();
            assertThat(expected.replaceAll("\\s", "")).contains(java.util.Base64.getEncoder()
                    .encodeToString(issuedKeys.getFirst().getPublic().getEncoded()));
            for (OrionMaterialReference invalid : List.of(new OrionMaterialReference("missing", 1),
                    new OrionMaterialReference("existing-account", 2), new OrionMaterialReference("signing", 1))) {
                assertThatThrownBy(() -> service.save(new AcmeConfigurationService.Settings("r2", "letsencrypt",
                        "", "admin@example.test", List.of("example.test"), "", null, invalid), "root"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(owner.configurationMaterial().inventory()).noneMatch(entry -> entry.alias().equals("missing"));
            assertThat(service.view().accountMaterial()).isEqualTo(reference);
        }
    }

    @Test
    void switchingExplicitAccountDoesNotReuseSavedEabCredentials() throws Exception {
        OrionDesiredState desired = new OrionDesiredState();
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        desired.publish(initial, Optional.of("r1"));
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            owner.administration().create("other-account", KeyMaterialPurpose.ACME_ACCOUNT, null);
            OrionConfiguration bootstrap = new OrionConfiguration();
            bootstrap.getBootstrap().getKeyMaterial().setClusterId("test");
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.current().document(), owner.configurationCipher());
            AcmeConfigurationService service = new AcmeConfigurationService(desired, secrets, null,
                    new AcmeCertificateService(bootstrap, desired, owner.acme(), null, secrets),
                    owner.configurationMaterial(), bootstrap);
            OrionDocument saved = service.updated(initial, settings("zerossl", "key-id", EAB_KEY));
            desired.publish(saved, Optional.of("r2"));
            assertThatThrownBy(() -> service.updated(saved, new AcmeConfigurationService.Settings("r2",
                    "zerossl", "", "admin@example.test", List.of("example.test"), "key-id", null,
                    new OrionMaterialReference("other-account", 1))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void enablesExistingShorthandDirectoryWithoutReplacingItsAccountKeyReference() {
        OrionDesiredState desired = new OrionDesiredState();
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(false,
                URI.create("acme://letsencrypt.org/staging"), "admin@example.test", List.of("example.test"),
                null, Optional.of(new OrionMaterialReference("account", 1)),
                60, 60, false, false, Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 8443, null,
                Optional.of(new OrionMaterialReference("identity", 1)), Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                List.of(), Optional.of(acme));
        OrionDocument initial = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.of(https), List.of(), List.of()), List.of());
        desired.publish(initial, Optional.of("r1"));
        AcmeConfigurationService service = new AcmeConfigurationService(desired, null, null,
                new AcmeCertificateService(new OrionConfiguration(), desired,
                        AcmeKeyMaterialCapability.unavailable(), null, null),
                ConfigurationMaterialCapability.unavailable(), new OrionConfiguration());
        AcmeConfigurationService.View view = service.view();
        assertThat(view.enabled()).isFalse();
        assertThat(view.directoryUrl()).isEqualTo("https://acme-staging-v02.api.letsencrypt.org/directory");
        OrionDocument updated = service.updated(initial, new AcmeConfigurationService.Settings(
                "r1", view.provider(), view.directoryUrl(), view.accountEmail(), view.domains(), "", null, null));
        OrionAcmeConfiguration enabled = updated.system().https().orElseThrow().acme().orElseThrow();
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.accountMaterial()).isEqualTo(acme.accountMaterial());
    }

    @Test
    void savesThroughTheConfigurationRepositoryAndRejectsStaleRevisions() throws Exception {
        OrionDesiredState desired = new OrionDesiredState();
        MemoryStorage storage = new MemoryStorage();
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.current().document(), owner.configurationCipher());
            OrionConfiguration bootstrap = new OrionConfiguration();
            bootstrap.getBootstrap().getKeyMaterial().setClusterId("test");
            OrionAccessControlServiceImpl acl = new OrionAccessControlServiceImpl(storage,
                    new OrionPasswordHashingService(),
                    OrionRuntimeOptions.defaults(), owner.serverIdentity(), desired,
                    bootstrap, owner.configurationCipher(), owner.configurationMaterial(), Optional.empty());
            acl.reload("test");
            List<KeyPair> accountKeys = new ArrayList<>();
            AcmeCertificateIssuer issuer = new AcmeCertificateIssuer(new AcmeHttpChallengeService()) {
                @Override
                public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
                    assertThat(org.slf4j.MDC.get("taskId")).isEqualTo("acme-certificate");
                    assertThat(org.slf4j.MDC.get("userId"))
                            .isEqualTo(accountKeys.size() < 2 ? "admin" : null);
                    assertThat(request.eabKeyId()).isEqualTo("key-id");
                    assertThat(request.eabHmacKey()).containsExactly(
                            EAB_KEY.toCharArray());
                    accountKeys.add(request.accountKeyPair());
                    try {
                        return new IssuedAcmeCertificate(request.domains(), List.of(
                                TestCertificateChain.selfSignedLeaf("example.test", request.domainKeyPair())));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            };
            AcmeCertificateService certificates = new AcmeCertificateService(
                    bootstrap, desired, owner.acme(), issuer, secrets);
            AcmeConfigurationService service = new AcmeConfigurationService(desired, secrets, acl, certificates,
                    owner.configurationMaterial(), bootstrap);

            AcmeConfigurationService.View result = service.save(settings("zerossl", "key-id", EAB_KEY), "root");

            assertThat(result.revision()).isEqualTo("r2");
            assertThat(result.eabConfigured()).isTrue();
            assertThat(storage.snapshot.files().get("orion.xml"))
                    .asString(StandardCharsets.UTF_8).doesNotContain(EAB_KEY);
            acl.reload("restart");
            assertThat(service.view()).isEqualTo(result);
            assertThatThrownBy(() -> service.save(settings("letsencrypt", "", ""), "root"))
                    .isInstanceOf(AccessControlConcurrentUpdateException.class);
            DefaultCommandDispatcher dispatcher = AcmeAdministrationTest.dispatcher(new AcmeCommandCatalog(service, certificates));
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request(
                    "/acme configure revision=r2 provider=zerossl email=admin@example.test "
                            + "domains=example.test eab-kid=key-id", AcmeAdministrationTest.admin())))
                    .isInstanceOf(CommandResult.ObjectValue.class);
            OrionAdminAcmeConfigurationRoute settingsRoute = new OrionAdminAcmeConfigurationRoute(
                    service, new ObjectMapper());
            assertThat(settingsRoute.doPost(AcmeAdministrationTest.httpRequest("POST",
                    "/api/admin/acme/configuration", """
                    {"revision":"r2","provider":"zerossl","accountEmail":"admin@example.test",
                     "domains":["example.test"],"eabKeyId":"key-id","eabHmacKey":""}
                    """, AcmeAdministrationTest.admin())).status()).isEqualTo(200);
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request("/acme issue",
                    AcmeAdministrationTest.admin())))
                    .isEqualTo(new CommandResult.Message("Certificate issued and saved."));
            AcmeCertificateService restarted = new AcmeCertificateService(
                    bootstrap, desired, owner.acme(), issuer, secrets);
            assertThat(new OrionAdminAcmeCertificateRoute(restarted, new ObjectMapper())
                    .doPost(AcmeAdministrationTest.httpRequest("POST", "/api/admin/acme/certificate", "",
                            AcmeAdministrationTest.admin())).status()).isEqualTo(200);
            Instant due = Instant.parse(restarted.renewalStatus().nextAttempt());
            restarted.maintainCertificate(due, () -> {});
            assertThat(accountKeys).hasSize(3);
            assertThat(service.view().renewal().state()).isEqualTo("stopped");
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request("/acme show",
                    AcmeAdministrationTest.admin())))
                    .isInstanceOfSatisfying(CommandResult.ObjectValue.class, shown ->
                            assertThat(shown.fields().get("nextAttempt").asText()).isNotBlank());
            assertThat(new ObjectMapper().writeValueAsString(service.view()))
                    .contains("lastSuccess", "nextAttempt", "stopped").doesNotContain(EAB_KEY);
            assertThat(accountKeys.get(2).getPrivate()).isEqualTo(accountKeys.get(0).getPrivate());
            assertThat(accountKeys.get(0).getPrivate()).isEqualTo(accountKeys.get(1).getPrivate());
            assertThat(restarted.savedCertificate()).isPresent();
            assertThatThrownBy(() -> restarted.issue(new AcmeCertificateService.IssueRequest(
                    "https://different-ca.example/directory", null, null, null, null, null, null)))
                    .isInstanceOf(HttpRequestValidationException.class);
        }
    }

    @Test
    void encryptsEabAndPreservesItWhenSavingWithoutAReplacement() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        OrionDesiredState desired = new OrionDesiredState();
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        desired.publish(initial, Optional.of("r1"));
        OrionDocument saved;
        try (OrionKeyMaterial owner = owner(store)) {
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.current().document(), owner.configurationCipher());
            AcmeConfigurationService service = new AcmeConfigurationService(desired, secrets, null,
                    new AcmeCertificateService(new OrionConfiguration(), desired, owner.acme(), null, secrets),
                    owner.configurationMaterial(), new OrionConfiguration());
            saved = service.updated(initial, settings("zerossl", "key-id", EAB_KEY));
            desired.publish(saved, Optional.of("r2"));
            OrionAcmeConfiguration acme = saved.system().https().orElseThrow().acme().orElseThrow();
            assertThat(acme.directoryUrl().toString()).isEqualTo("https://acme.zerossl.com/v2/DV90");
            assertThat(saved.toString()).doesNotContain(EAB_KEY);
            assertThat(service.view().eabConfigured()).isTrue();
            assertThat(service.view().toString()).doesNotContain(EAB_KEY);
            assertThat(service.updated(saved, settings("zerossl", "key-id", ""))).isEqualTo(saved);
            assertThatThrownBy(() -> service.updated(saved, settings("google", "key-id", "")))
                    .isInstanceOf(IllegalArgumentException.class);
            OrionDocument switched = service.updated(saved, settings("google", "new-key-id", EAB_KEY));
            assertThat(switched.system().https().orElseThrow().identity())
                    .isEqualTo(saved.system().https().orElseThrow().identity());
            assertThat(switched.system().https().orElseThrow().acme().orElseThrow().accountMaterial())
                    .isNotEqualTo(acme.accountMaterial());
        }
        try (OrionKeyMaterial owner = owner(store)) {
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.current().document(), owner.configurationCipher());
            String reference = saved.system().https().orElseThrow().acme().orElseThrow().eabSecret().orElseThrow();
            assertThat(secrets.resolveSystem(reference)).containsExactly(EAB_KEY.toCharArray());
        }
    }

    @Test
    void rejectsIncompleteEabAndAllowsLetsEncryptWithoutIt() throws Exception {
        OrionDesiredState desired = new OrionDesiredState();
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        desired.publish(initial, Optional.of("r1"));
        AcmeConfigurationService service = new AcmeConfigurationService(desired, null, null,
                new AcmeCertificateService(new OrionConfiguration(), desired,
                        AcmeKeyMaterialCapability.unavailable(), null, null),
                ConfigurationMaterialCapability.unavailable(), new OrionConfiguration());
        assertThatThrownBy(() -> service.updated(initial, settings("zerossl", "", "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updated(initial, settings("custom", "id", "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updated(initial, settings("custom", "id", "not+base64url")))
                .isInstanceOf(IllegalArgumentException.class);
        OrionAcmeConfiguration acme = service.updated(initial, settings("letsencrypt", "", ""))
                .system().https().orElseThrow().acme().orElseThrow();
        assertThat(acme.eabSecret()).isEmpty();
        assertThat(acme.agreeToTermsOfService()).isTrue();
    }

    private static OrionKeyMaterial owner(InMemoryKeyMaterialContentStore store) throws Exception {
        return OrionKeyMaterial.open(store, KeyMaterialOptions.pkcs12("password".toCharArray()),
                new SigningMaterialSet(
                        new KeyMaterialDescriptor(
                                new KeyMaterialAlias("signing"),
                                KeyMaterialPurpose.SERVER_SIGNING,
                                KeyMaterialAlgorithm.RSA,
                                new KeyMaterialVersion(1),
                                KeyMaterialScope.cluster("test")), List.of()), 2048, true);
    }

    private static AcmeConfigurationService.Settings settings(String provider, String kid, String key) {
        return new AcmeConfigurationService.Settings("r1", provider, "https://ca.example/directory",
                "admin@example.test", List.of("example.test"), kid, key.toCharArray(), null);
    }

    private static final class MemoryStorage implements AccessControlStorage {
        private AccessControlSnapshot snapshot;

        private MemoryStorage() throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            OrionXml.write(
                    OrionDocument.withAccessControl(new AccessControl()), output);
            snapshot = new AccessControlSnapshot(
                    Map.of("orion.xml", output.toByteArray()), Optional.of("r1"));
        }

        @Override
        public Result<AccessControlSnapshot> load() {
            return new Result.Success<>(snapshot);
        }

        @Override
        public String primaryPath() { return "orion.xml"; }

        @Override
        public void save(AccessControlSnapshot next,
                AccessControlSaveRequest request) {
            snapshot = new AccessControlSnapshot(next.files(), Optional.of("r2"));
        }
    }
}
