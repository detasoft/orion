package pro.deta.orion.acme;

import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;

import java.util.Set;

import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.test.integration.TestConfiguration;
import java.net.URI;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.storage.InMemoryBlobStorage;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeConfigurationTest {
    @org.junit.jupiter.api.extension.RegisterExtension
    final pro.deta.orion.test.util.OrionTestRuntime events =
            new pro.deta.orion.test.util.OrionTestRuntime();

    private static final String EAB_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void switchingExplicitAccountDoesNotReuseSavedEabCredentials() throws Exception {
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            owner.withModification(owner.revision(), modification -> {
                modification.generateKey(new KeyMaterialDescriptor("other-account",
                        Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 1), 3072);
                modification.apply();
            });
            BootstrapConfiguration bootstrap = new BootstrapConfiguration();
            TestConfiguration desired = new TestConfiguration(events, bootstrap,
                    owner);
            desired.publish(initial, Optional.of("r1"));
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, null, secrets, events.executor());
            OrionDocument saved = save(service, desired, "zerossl", "key-id", EAB_KEY);
            assertThatThrownBy(() -> service.save(new AcmeCertificateService.Settings(service.view().revision(),
                    "zerossl", "", "admin@example.test", List.of("example.test"), "key-id", null,
                    new KeyMaterialId("other-account", 1)), "root"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(desired.stored().result().valueOrFailure("configuration")).isEqualTo(saved);
        }
    }

    @Test
    void enablesExistingShorthandDirectoryWithoutReplacingItsAccountKeyReference() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            TestConfiguration desired = new TestConfiguration(events, new BootstrapConfiguration(), owner);
            OrionAcmeConfiguration acme = new OrionAcmeConfiguration(false,
                    URI.create("acme://letsencrypt.org/staging"), "admin@example.test", List.of("example.test"),
                    null, Optional.of(new KeyMaterialId("account", 1)),
                    60, 60, false, false, Optional.empty(), Optional.empty());
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 8443, null,
                    Optional.of(new KeyMaterialId("identity", 1)), Optional.empty(),
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                    List.of(), Optional.of(acme));
            OrionDocument initial = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                    Optional.of(https), List.of(), List.of(), List.of(),
                    List.of(), List.of()), List.of());
            KeyMaterialDescriptor account = new KeyMaterialDescriptor("account",
                    Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA, 1);
            owner.withModification(owner.revision(), modification -> {
                modification.generateKey(account, 2048);
                modification.generateKey(new KeyMaterialDescriptor("identity",
                        Set.of(KeyMaterialPurpose.TLS_IDENTITY), KeyMaterialAlgorithm.RSA, 1), 2048);
                modification.apply();
            });
            byte[] accountKey = owner.read(account.id()).keyPair().getPublic().getEncoded();
            desired.publish(initial, Optional.of("r1"));
            AcmeCertificateService service = new AcmeCertificateService(desired.editor(),
                    owner, null, null, events.executor());
            AcmeCertificateService.View view = service.view();
            assertThat(view.enabled()).isFalse();
            assertThat(view.directoryUrl()).isEqualTo("https://acme-staging-v02.api.letsencrypt.org/directory");
            service.save(new AcmeCertificateService.Settings(
                    "r1", view.provider(), view.directoryUrl(), view.accountEmail(), view.domains(),
                    "", null, null), "root");
            events.awaitEvents();
            OrionDocument updated = desired.stored().result().valueOrFailure("configuration");
            OrionAcmeConfiguration enabled = updated.system().https().orElseThrow().acme().orElseThrow();
            assertThat(enabled.enabled()).isTrue();
            assertThat(enabled.accountMaterial()).isEqualTo(acme.accountMaterial());
            assertThat(owner.read(account.id()).keyPair().getPublic().getEncoded()).isEqualTo(accountKey);
        }
    }

    @Test
    void encryptsEabAndPreservesItWhenSavingWithoutAReplacement() throws Exception {
        InMemoryBlobStorage store = new InMemoryBlobStorage();
        OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
        OrionDocument saved;
        try (OrionKeyMaterial owner = owner(store)) {
            BootstrapConfiguration bootstrap = new BootstrapConfiguration();
            TestConfiguration desired = new TestConfiguration(events, bootstrap, owner);
            desired.publish(initial, Optional.of("r1"));
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, null, secrets, events.executor());
            saved = save(service, desired, "zerossl", "key-id", EAB_KEY);
            OrionAcmeConfiguration acme = saved.system().https().orElseThrow().acme().orElseThrow();
            assertThat(acme.directoryUrl().toString()).isEqualTo("https://acme.zerossl.com/v2/DV90");
            assertThat(saved.toString()).doesNotContain(EAB_KEY);
            assertThat(service.view().eabConfigured()).isTrue();
            assertThat(service.view().toString()).doesNotContain(EAB_KEY);
            assertThat(save(service, desired, "zerossl", "key-id", "")).isEqualTo(saved);
            assertThatThrownBy(() -> save(service, desired, "google", "key-id", ""))
                    .isInstanceOf(IllegalArgumentException.class);
            OrionDocument switched = save(service, desired, "google", "new-key-id", EAB_KEY);
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
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            TestConfiguration desired = new TestConfiguration(events, new BootstrapConfiguration(), owner);
            desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.of("r1"));
            ConfigurationSecrets secrets = new ConfigurationSecrets(
                    () -> desired.editor().current().result()
                            .valueOrFailure("Cannot read configuration document"), owner);
            AcmeCertificateService service = new AcmeCertificateService(
                    desired.editor(), owner, null, secrets, events.executor());
            OrionDocument saved = save(service, desired, "zerossl", "key-id", EAB_KEY);
            String original = saved.system().https().orElseThrow().acme().orElseThrow().eabSecret().orElseThrow();
            Connection.S3 connection = new Connection.S3("archive", Optional.empty(), "us-east-1",
                    false, Optional.of("id"), Optional.of(original), Optional.empty());
            saved = new OrionDocument(new OrionDocument.SystemConfiguration(saved.system().accessControl(),
                    saved.system().https(), saved.system().secrets(), saved.system().proxies(), List.of(connection),
                    saved.system().oidcProviders(), saved.system().invitations()),
                    saved.organizations());
            desired.publish(saved, Optional.of("with-connection"));
            String changedKey = java.util.Base64.getUrlEncoder().encodeToString(new byte[32]);
            OrionDocument changed = save(service, desired, "zerossl", "key-id", changedKey);
            String replacement = changed.system().https().orElseThrow().acme().orElseThrow().eabSecret().orElseThrow();
            assertThat(replacement).isNotEqualTo(original);
            assertThat(secrets.resolveSystem(changed, original)).isEqualTo(EAB_KEY.toCharArray());
            assertThat(secrets.resolveSystem(changed, replacement)).isEqualTo(changedKey.toCharArray());
            assertThat(changed.system().connections()).containsExactly(connection);
        }
    }

    @Test
    void rejectsIncompleteEabAndAllowsLetsEncryptWithoutIt() throws Exception {
        try (OrionKeyMaterial owner = owner(new InMemoryBlobStorage())) {
            TestConfiguration desired = new TestConfiguration(events, new BootstrapConfiguration(), owner);
            OrionDocument initial = OrionDocument.withAccessControl(new AccessControl());
            desired.publish(initial, Optional.of("r1"));
            AcmeCertificateService service = new AcmeCertificateService(desired.editor(),
                    owner, null, null, events.executor());
            assertThatThrownBy(() -> save(service, desired, "zerossl", "", ""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> save(service, desired, "custom", "id", ""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> save(service, desired, "custom", "id", "not+base64url"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(desired.stored().result().valueOrFailure("configuration")).isEqualTo(initial);
            OrionAcmeConfiguration acme = save(service, desired, "letsencrypt", "", "")
                    .system().https().orElseThrow().acme().orElseThrow();
            assertThat(acme.eabSecret()).isEmpty();
            assertThat(acme.agreeToTermsOfService()).isTrue();
        }
    }

    private OrionKeyMaterial owner(InMemoryBlobStorage store) throws Exception {
        return OrionKeyMaterial.open(new KeyMaterialRepository(
                store,
                new KeyMaterialCodec(KeyMaterialOptions.pkcs12("password".toCharArray())),
                events.unstartedEvents()));
    }

    private OrionDocument save(AcmeCertificateService service, TestConfiguration desired,
            String provider, String kid, String key) {
        AcmeCertificateService.View saved = service.save(new AcmeCertificateService.Settings(
                service.view().revision(), provider, "https://ca.example/directory", "admin@example.test",
                List.of("example.test"), kid, key.toCharArray(), null), "root");
        events.awaitEvents();
        assertThat(desired.stored().revision())
                .isEqualTo(new pro.deta.orion.config.OrionRevision.Stored(saved.revision()));
        return desired.stored().result().valueOrFailure("configuration");
    }
}
