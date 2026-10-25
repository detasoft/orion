package pro.deta.orion;

import java.security.KeyStore;
import java.io.ByteArrayOutputStream;

import pro.deta.orion.keymaterial.KeyMaterialCreation;
import pro.deta.orion.event.OrionEventManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import java.util.Set;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.bootstrap.config.SigningKeyReferenceConfig;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialBootstrapTest {
    private static final String PASSWORD_ENV = "ORION_TEST_KEY_MATERIAL_PASSWORD";
    private final OrionEventManager events = new OrionEventManager();

    @TempDir
    private Path tempDir;

    @Test
    void opensConfiguredRelativeStoreAndReloadsRetainedIdentity() throws Exception {
        BootstrapConfiguration configuration = configuration();
        byte[] payload = "jwt-input".getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        try (OrionKeyMaterial material = openLocal(configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                new KeyMaterialCreation(true))) {
            KeyMaterialDescriptor active = signing(configuration).active();
            signature = material.sign(active, payload);
            assertThat(active.alias()).isEqualTo("cluster-signing-v2");
            assertThat(material.version()).isEqualTo(1);
        }

        try (OrionKeyMaterial material = openLocal(configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                new KeyMaterialCreation(false))) {
            assertThat(material.verify(signing(configuration).active(), payload, signature)).isTrue();
        }

        assertThat(Files.isRegularFile(tempDir.resolve("security/orion.p12"))).isTrue();
    }

    @Test
    void resolvesRelativeStoreBelowEnvironmentBaseDirectory() throws Exception {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().setBaseDir("env:ORION_TEST_ROOT/material-home");

        try (OrionKeyMaterial ignored = openLocal(configuration,
                Map.of(
                        "ORION_TEST_ROOT", tempDir.toString(),
                        PASSWORD_ENV, "test-password"),
                new KeyMaterialCreation(true))) {
            // Opening the identity initializes the configured store.
        }

        assertThat(Files.isRegularFile(tempDir.resolve("material-home/security/orion.p12"))).isTrue();
    }

    @Test
    void resolvesConfiguredRetainedAliases() throws Exception {
        BootstrapConfiguration configuration = configuration();
        try (OrionKeyMaterial ignored = openLocal(configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                new KeyMaterialCreation(true))) {
            // Initialize the active alias in a new store.
        }
        configuration.getBootstrap()
                .getKeyMaterial()
                .getServerSigning()
                .setVerification(List.of(new SigningKeyReferenceConfig("missing-retained", 1)));

        assertThatThrownBy(() -> openLocal(configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                new KeyMaterialCreation(false)))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("missing-retained");
    }

    @Test
    void missingProtectedPasswordFailsWithoutCreatingStore() {
        BootstrapConfiguration configuration = configuration();

        assertThatThrownBy(() -> openLocal(configuration,
                Map.of(),
                new KeyMaterialCreation(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PASSWORD_ENV);
        assertThat(Files.exists(tempDir.resolve("security/orion.p12"))).isFalse();
    }

    @Test
    void opensIdentityFromAlreadyResolvedContentStore() throws Exception {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().setLocation("git+https://example.test/private.git");
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        try (OrionKeyMaterial material = KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                store,
                new KeyMaterialCreation(true),
                events)) {
            assertThat(material.contains(signing(configuration).active())).isTrue();
        }

        assertThat(store.read()).isPresent();
    }

    @Test
    void requiresExplicitCreationAndDoesNotPopulateAnExistingEmptyStore() throws Exception {
        BootstrapConfiguration configuration = configuration();
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                store,
                new KeyMaterialCreation(false),
                events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("creation was not requested");
        assertThat(store.read()).isEmpty();

        KeyStore emptyStore = KeyStore.getInstance("PKCS12");
        char[] password = "test-password".toCharArray();
        emptyStore.load(null, password);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            emptyStore.store(output, password);
            store.write(output.toByteArray(), null);
        }
        byte[] before = store.read().orElseThrow().bytes();
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                store,
                new KeyMaterialCreation(true),
                events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("cluster-signing-v2");
        assertThat(store.read().orElseThrow().bytes()).isEqualTo(before);
    }

    @Test
    void doesNotAddTheConfiguredSigningKeyToAnExistingStore() throws Exception {
        BootstrapConfiguration configuration = configuration();
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray());
             OrionKeyMaterial material = OrionKeyMaterial.open(store, options)) {
            material.withModification(modification -> {
                modification.generateKey(new KeyMaterialDescriptor("other-signing",
                        Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1), 2048);
                modification.apply();
            });
        }
        byte[] before = store.read().orElseThrow().bytes();
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration, Map.of(PASSWORD_ENV, "test-password"), store,
                new KeyMaterialCreation(true), events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("cluster-signing-v2");
        assertThat(store.read().orElseThrow().bytes()).isEqualTo(before);
    }

    @Test
    void rejectsRetainedKeysOnCreationWithoutWritingTheStore() {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().getServerSigning()
                .setVerification(List.of(new SigningKeyReferenceConfig("previous-signing", 1)));
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                store,
                new KeyMaterialCreation(true),
                events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("retained");
        assertThat(store.read()).isEmpty();
    }

    @Test
    void rejectsNonRsaSigningConfigurationBeforeCreatingTheStore() {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().getServerSigning().setAlgorithm("ED25519");
        AtomicInteger closes = new AtomicInteger();
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore() {
            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                store,
                new KeyMaterialCreation(true),
                events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RSA");
        assertThat(store.read()).isEmpty();
        assertThat(closes).hasValue(1);
    }

    private OrionKeyMaterial openLocal(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            KeyMaterialCreation creation) throws Exception {
        return KeyMaterialBootstrap.open(configuration, environment,
                KeyMaterialBootstrap.localStore(configuration, environment), creation, events);
    }

    private static SigningMaterialSet signing(BootstrapConfiguration configuration) {
        return KeyMaterialBootstrap.signingMaterial(configuration.getBootstrap().getKeyMaterial());
    }

    private BootstrapConfiguration configuration() {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getBootstrap().getKeyMaterial().setLocation("security/orion.p12");
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        configuration.getBootstrap()
                .getKeyMaterial()
                .getServerSigning()
                .setActive(new SigningKeyReferenceConfig("cluster-signing-v2", 2));
        return configuration;
    }
}
