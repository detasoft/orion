package pro.deta.orion.acme;

import java.util.Set;

import pro.deta.orion.keymaterial.KeyMaterialCreation;

import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.test.integration.TestConfiguration;
import java.net.URI;
import pro.deta.orion.schema.orion.v2.OrionMaterialReference;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeConfigurationTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    private static final String EAB_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void switchingExplicitAccountDoesNotReuseSavedEabCredentials() throws Exception {
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            owner.withModification(modification -> {
                modification.generateKey(new KeyMaterialDescriptor("other-account",
                        Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 1), 3072);
                modification.apply();
            });
            BootstrapConfiguration bootstrap = new BootstrapConfiguration();
            TestConfiguration desired = new TestConfiguration(EVENTS, bootstrap,
                    owner);
            desired.publish(initial, Optional.of("r1"));
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, null, secrets, EVENTS.executor());
            OrionDocument saved = service.updated(initial, settings("zerossl", "key-id", EAB_KEY));
            desired.publish(saved, Optional.of("r2"));
            assertThatThrownBy(() -> service.updated(saved, new AcmeCertificateService.Settings("r2",
                    "zerossl", "", "admin@example.test", List.of("example.test"), "key-id", null,
                    new OrionMaterialReference("other-account", 1))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void enablesExistingShorthandDirectoryWithoutReplacingItsAccountKeyReference() {
        TestConfiguration desired = new TestConfiguration(EVENTS);
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(false,
                URI.create("acme://letsencrypt.org/staging"), "admin@example.test", List.of("example.test"),
                null, Optional.of(new OrionMaterialReference("account", 1)),
                60, 60, false, false, Optional.empty(), Optional.empty());
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 8443, null,
                Optional.of(new OrionMaterialReference("identity", 1)), Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                List.of(), Optional.of(acme));
        OrionDocument initial = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                Optional.of(https), List.of(), List.of(), List.of(),
                List.of(), List.of()), List.of());
        desired.publish(initial, Optional.of("r1"));
        AcmeCertificateService service = new AcmeCertificateService(desired.editor(),
                        OrionKeyMaterial.unavailable(), null, null, EVENTS.executor());
        AcmeCertificateService.View view = service.view();
        assertThat(view.enabled()).isFalse();
        assertThat(view.directoryUrl()).isEqualTo("https://acme-staging-v02.api.letsencrypt.org/directory");
        OrionDocument updated = service.updated(initial, new AcmeCertificateService.Settings(
                "r1", view.provider(), view.directoryUrl(), view.accountEmail(), view.domains(), "", null, null));
        OrionAcmeConfiguration enabled = updated.system().https().orElseThrow().acme().orElseThrow();
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.accountMaterial()).isEqualTo(acme.accountMaterial());
    }

    @Test
    void encryptsEabAndPreservesItWhenSavingWithoutAReplacement() throws Exception {
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        OrionDocument saved;
        try (OrionKeyMaterial owner = owner(store)) {
            BootstrapConfiguration bootstrap = new BootstrapConfiguration();
            TestConfiguration desired = new TestConfiguration(EVENTS, bootstrap, owner);
            desired.publish(initial, Optional.of("r1"));
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, null, secrets, EVENTS.executor());
            saved = service.updated(initial, settings("zerossl", "key-id", EAB_KEY));
            service.prepareMaterial(saved.system().https().orElseThrow());
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
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> saved, owner);
            String reference = saved.system().https().orElseThrow().acme().orElseThrow().eabSecret().orElseThrow();
            assertThat(secrets.resolveSystem(reference)).containsExactly(EAB_KEY.toCharArray());
        }
    }

    @Test
    void replacingEabDoesNotOverwriteACredentialsSecretUsedByAnS3Connection() throws Exception {
        TestConfiguration desired = new TestConfiguration(EVENTS);
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        desired.publish(initial, Optional.of("r1"));
        try (OrionKeyMaterial owner = owner(new InMemoryKeyMaterialContentStore())) {
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, null, secrets, EVENTS.executor());
            OrionDocument saved = service.updated(initial, settings("zerossl", "key-id", EAB_KEY));
            String original = saved.system().https().orElseThrow().acme().orElseThrow().eabSecret().orElseThrow();
            Connection.S3 connection = new Connection.S3("archive", Optional.empty(), "us-east-1",
                    false, Optional.of("id"), Optional.of(original), Optional.empty());
            saved = new OrionDocument(new OrionDocument.SystemConfiguration(saved.system().accessControl(),
                    saved.system().https(), saved.system().secrets(), saved.system().proxies(), List.of(connection),
                    saved.system().oidcProviders(), saved.system().invitations()),
                    saved.organizations());
            String changedKey = java.util.Base64.getUrlEncoder().encodeToString(new byte[32]);
            OrionDocument changed = service.updated(saved, settings("zerossl", "key-id", changedKey));
            String replacement = changed.system().https().orElseThrow().acme().orElseThrow().eabSecret().orElseThrow();
            assertThat(replacement).isNotEqualTo(original);
            assertThat(secrets.resolveSystem(changed, original)).isEqualTo(EAB_KEY.toCharArray());
            assertThat(secrets.resolveSystem(changed, replacement)).isEqualTo(changedKey.toCharArray());
            assertThat(changed.system().connections()).containsExactly(connection);
        }
    }

    @Test
    void rejectsIncompleteEabAndAllowsLetsEncryptWithoutIt() throws Exception {
        TestConfiguration desired = new TestConfiguration(EVENTS);
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        desired.publish(initial, Optional.of("r1"));
        AcmeCertificateService service = new AcmeCertificateService(desired.editor(),
                        OrionKeyMaterial.unavailable(), null, null, EVENTS.executor());
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
                                "signing",
                                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                                KeyMaterialAlgorithm.RSA,
                                1), List.of()), 2048,
                                        new KeyMaterialCreation(true));
    }

    private static AcmeCertificateService.Settings settings(String provider, String kid, String key) {
        return new AcmeCertificateService.Settings("r1", provider, "https://ca.example/directory",
                "admin@example.test", List.of("example.test"), kid, key.toCharArray(), null);
    }

}
