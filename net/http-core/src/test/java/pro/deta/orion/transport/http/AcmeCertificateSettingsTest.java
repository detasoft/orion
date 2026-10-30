package pro.deta.orion.transport.http;

import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialContentStore;

import pro.deta.orion.acme.AcmeCertificateService;
import pro.deta.orion.acme.AcmeCertificateIssuer;
import pro.deta.orion.acme.AcmeCertificateIssueRequest;
import pro.deta.orion.acme.IssuedAcmeCertificate;
import pro.deta.orion.acme.AcmeHttpChallengeService;
import pro.deta.orion.test.certificates.TestCertificateChain;

import java.util.Set;

import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.test.integration.TestConfiguration;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import pro.deta.orion.util.OrionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import pro.deta.orion.keymaterial.KeyMaterialId;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentSource;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeCertificateSettingsTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime events =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private static final String EAB_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void selectsStoredAccountKeyPersistsItsReferenceAndReusesItForIssuance() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentSource())) {
            owner.withModification(owner.revision(), modification -> {
                modification.generateKey(new KeyMaterialDescriptor("existing-account",
                        Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 1), 3072);
                modification.apply();
            });
            KeyMaterialId reference = new KeyMaterialId("existing-account", 1);
            BootstrapConfiguration bootstrap = new BootstrapConfiguration();
            TestConfiguration desired = new TestConfiguration(events, bootstrap,
                    owner);
            desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.of("r1"));
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
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
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, issuer, secrets, events.executor());
            AcmeCertificateService.View view = service.save(new AcmeCertificateService.Settings("r1",
                    "letsencrypt", "", "admin@example.test", List.of("example.test"), "", null, reference), "root");
            assertThat(view.accountMaterial()).isEqualTo(reference);
            assertThat(desired.stored().result().valueOrFailure("Cannot read configuration document")
                    .system().https().orElseThrow().acme().orElseThrow().accountMaterial()).contains(reference);
            events.awaitEvents();
            assertThat(service.view().accountMaterial()).isEqualTo(reference);
            DefaultCommandDispatcher dispatcher = AcmeAdministrationTest.dispatcher(
                    new AcmeCommandCatalog(service, null));
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request(
                    "/acme configure revision=saved-1 provider=letsencrypt email=admin@example.test "
                            + "domains=example.test account-key=existing-account account-key-version=1",
                    AcmeAdministrationTest.admin())))
                    .isInstanceOfSatisfying(CommandResult.ObjectValue.class, result ->
                            assertThat(result.fields().get("accountKey").asText()).isEqualTo("existing-account"));
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request(
                    "/acme configure account-key=existing-account", AcmeAdministrationTest.admin())))
                    .isInstanceOf(CommandResult.Failure.class);
            OrionAdminAcmeConfigurationRoute route =
                    new OrionAdminAcmeConfigurationRoute(service, new ObjectMapper(), null);
            assertThat(route.doPost(AcmeAdministrationTest.httpRequest("POST", "/api/admin/acme/configuration", """
                    {"revision":"saved-1","provider":"letsencrypt","accountEmail":"admin@example.test",
                     "domains":["example.test"],"accountMaterial":{"alias":"existing-account","version":1}}
                    """, AcmeAdministrationTest.admin())).status()).isEqualTo(200);
            service.issue(new AcmeCertificateService.IssueRequest(null, null, null, null, null, null, true));
            assertThat(issuedKeys).hasSize(1);
            String expected = owner.inventory(Set.of()).getFirst().publicKeyPem();
            assertThat(expected.replaceAll("\\s", "")).contains(java.util.Base64.getEncoder()
                    .encodeToString(issuedKeys.getFirst().getPublic().getEncoded()));
            for (KeyMaterialId invalid : List.of(new KeyMaterialId("missing", 1),
                    new KeyMaterialId("existing-account", 2), new KeyMaterialId("signing", 1))) {
                assertThatThrownBy(
                                () ->
                                        service.save(
                                                new AcmeCertificateService.Settings(
                                                        "saved-1",
                                                        "letsencrypt",
                                                        "",
                                                        "admin@example.test",
                                                        List.of("example.test"),
                                                        "",
                                                        null,
                                                        invalid),
                                                "root"))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(owner.inventory(Set.of())).noneMatch(entry -> entry.descriptor().alias().equals("missing"));
            assertThat(service.view().accountMaterial()).isEqualTo(reference);
        }
    }

    @Test
    void savesThroughTheConfigurationRepositoryAndRejectsStaleRevisions() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentSource())) {
            BootstrapConfiguration bootstrap = new BootstrapConfiguration();
            TestConfiguration desired = new TestConfiguration(events, bootstrap,
                    owner);
            desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.of("r1"));
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
            List<KeyPair> accountKeys = new CopyOnWriteArrayList<>();
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
                                TestCertificateChain.selfSignedLeaf("example.test", request.domainKeyPair(),
                                        Instant.now().minus(Duration.ofDays(80)),
                                        Instant.now().plus(Duration.ofDays(10)))));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
            };
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, issuer, secrets, events.executor());

            AcmeCertificateService.Settings input = settings("zerossl", "key-id", EAB_KEY);
            AcmeCertificateService.View result = service.save(input, "root");

            assertThat(input.eabHmacKey()).containsOnly('\0');
            assertThat(result.revision()).isEqualTo("saved-1");
            assertThat(result.eabConfigured()).isTrue();
            assertThat(desired.stored().result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().secrets())
                    .extracting(pro.deta.orion.schema.orion.v2.ConfigurationSecret::envelope)
                    .allSatisfy(value -> assertThat(value).doesNotContain(EAB_KEY));
            events.awaitEvents();
            assertThat(service.view()).usingRecursiveComparison().ignoringFields("renewal").isEqualTo(result);
            AcmeCertificateService.Settings stale = settings("zerossl", "key-id", EAB_KEY);
            assertThatThrownBy(() -> service.save(stale, "root"))
                    .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            assertThat(stale.eabHmacKey()).containsOnly('\0');
            DefaultCommandDispatcher dispatcher =
                    AcmeAdministrationTest.dispatcher(new AcmeCommandCatalog(service, null));
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request(
                    "/acme configure revision=saved-1 provider=zerossl email=admin@example.test "
                            + "domains=example.test eab-kid=key-id", AcmeAdministrationTest.admin())))
                    .isInstanceOf(CommandResult.ObjectValue.class);
            OrionAdminAcmeConfigurationRoute settingsRoute = new OrionAdminAcmeConfigurationRoute(
                    service, new ObjectMapper(), null);
            assertThat(settingsRoute.doPost(AcmeAdministrationTest.httpRequest("POST",
                    "/api/admin/acme/configuration", """
                    {"revision":"saved-1","provider":"zerossl","accountEmail":"admin@example.test",
                     "domains":["example.test"],"eabKeyId":"key-id","eabHmacKey":""}
                    """, AcmeAdministrationTest.admin())).status()).isEqualTo(200);
            assertThat(dispatcher.dispatch(AcmeAdministrationTest.request("/acme issue",
                    AcmeAdministrationTest.admin())))
                    .isEqualTo(new CommandResult.Message("Certificate issued and saved."));
            AcmeCertificateService restarted = new AcmeCertificateService(
                    desired.editor(), owner, issuer, secrets, events.executor());
            assertThat(new OrionAdminAcmeCertificateRoute(restarted, new ObjectMapper())
                    .doPost(AcmeAdministrationTest.httpRequest("POST", "/api/admin/acme/certificate", "",
                            AcmeAdministrationTest.admin())).status()).isEqualTo(200);
            AcmeCertificateService renewal = new AcmeCertificateService(
                    desired.editor(), owner, issuer, secrets, events.executor());
            renewal.onStart();
            try {
                assertThat(OrionUtils.waitForCondition(() -> accountKeys.size() == 3
                        && !renewal.renewalStatus().lastSuccess().isEmpty())).isTrue();
            } finally {
                renewal.onStop();
            }
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
                    .isInstanceOf(AcmeCertificateService.ValidationException.class);
            OrionAdminAcmeCertificateRoute route =
                    new OrionAdminAcmeCertificateRoute(restarted, new ObjectMapper());
            assertThatThrownBy(() -> route.doPost(AcmeAdministrationTest.httpRequest(
                    "POST", "/api/admin/acme/certificate",
                    "{\"directoryUrl\":\"https://different-ca.example/directory\"}",
                    AcmeAdministrationTest.admin())))
                    .isInstanceOf(HttpRequestValidationException.class)
                    .hasMessage("Save ACME provider settings before changing the directory URL");
        }
    }

    private OrionKeyMaterial owner(InMemoryKeyMaterialContentSource store) throws Exception {
        return OrionKeyMaterial.open(new KeyMaterialContentStore(
                store,
                new KeyMaterialCodec(KeyMaterialOptions.pkcs12("password".toCharArray())),
                events.unstartedEvents()));
    }

    private static AcmeCertificateService.Settings settings(String provider, String kid, String key) {
        return new AcmeCertificateService.Settings("r1", provider, "https://ca.example/directory",
                "admin@example.test", List.of("example.test"), kid, key.toCharArray(), null);
    }

}
