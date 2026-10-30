package pro.deta.orion;

import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialContentStore;

import java.security.KeyStore;
import java.io.ByteArrayOutputStream;

import pro.deta.orion.event.OrionEventManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.keymaterial.LocalKeyMaterialContentSource;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryBackend;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyMaterialBootstrapTest {
    private static final String PASSWORD_ENV = "ORION_TEST_KEY_MATERIAL_PASSWORD";
    private final OrionEventManager events = new OrionEventManager();

    private final NativeGitRepositoryFactory repositories = new NativeGitRepositoryFactory(
            NativeGitRepositoryBackend.inMemory(events));

    @AfterEach
    void closeRepositories() {
        repositories.provider().close();
    }

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
            assertThat(material.revision()).isEqualTo(new LocalKeyMaterialContentSource(
                    tempDir.resolve("security/orion.p12")).load().revision());
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
    void createsAndReopensIdentityInConfiguredGitFile() throws Exception {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().setLocation("local:material");
        configuration.getBootstrap().getKeyMaterial().setPath("security/material.p12");
        repositories.provider().create("material").valueOrFailure("create material repository");

        try (OrionKeyMaterial material = KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                repositories,
                new KeyMaterialCreation(true),
                events)) {
            assertThat(material.contains(signing(configuration).active())).isTrue();
        }

        try (OrionKeyMaterial material = KeyMaterialBootstrap.open(
                configuration, Map.of(PASSWORD_ENV, "test-password"), repositories,
                new KeyMaterialCreation(false), events)) {
            assertThat(material.contains(signing(configuration).active())).isTrue();
        }
        assertThat(Files.exists(tempDir.resolve("security/orion.p12"))).isFalse();
    }

    @Test
    void requiresExplicitCreationAndDoesNotPopulateAnExistingEmptyStore() throws Exception {
        BootstrapConfiguration configuration = configuration();
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(
                tempDir.resolve("security/orion.p12"));
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                repositories,
                new KeyMaterialCreation(false),
                events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("creation was not requested");
        assertThat(store.load().bytes()).isNull();

        KeyStore emptyStore = KeyStore.getInstance("PKCS12");
        char[] password = "test-password".toCharArray();
        emptyStore.load(null, password);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            emptyStore.store(output, password);
            store.write(output.toByteArray(), null);
        }
        byte[] before = store.load().bytes();
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                repositories,
                new KeyMaterialCreation(true),
                events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("cluster-signing-v2");
        assertThat(store.load().bytes()).isEqualTo(before);
    }

    @Test
    void doesNotAddTheConfiguredSigningKeyToAnExistingStore() throws Exception {
        BootstrapConfiguration configuration = configuration();
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(
                tempDir.resolve("security/orion.p12"));
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-password".toCharArray());
             OrionKeyMaterial material = OrionKeyMaterial.open(new KeyMaterialContentStore(
                     store,
                     new KeyMaterialCodec(options),
                     events))) {
            material.withModification(material.revision(), modification -> {
                modification.generateKey(new KeyMaterialDescriptor("other-signing",
                        Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, 1), 2048);
                modification.apply();
            });
        }
        byte[] before = store.load().bytes();
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration, Map.of(PASSWORD_ENV, "test-password"), repositories,
                new KeyMaterialCreation(true), events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("cluster-signing-v2");
        assertThat(store.load().bytes()).isEqualTo(before);
    }

    @Test
    void rejectsRetainedKeysOnCreationWithoutWritingTheStore() throws Exception {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().getServerSigning()
                .setVerification(List.of(new SigningKeyReferenceConfig("previous-signing", 1)));
        LocalKeyMaterialContentSource store = new LocalKeyMaterialContentSource(
                tempDir.resolve("security/orion.p12"));
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                repositories,
                new KeyMaterialCreation(true),
                events))
                .isInstanceOf(GeneralSecurityException.class)
                .hasMessageContaining("retained");
        assertThat(store.load().bytes()).isNull();
    }

    @Test
    void rejectsNonRsaSigningConfigurationBeforeCreatingTheStore() {
        BootstrapConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().getServerSigning().setAlgorithm("ED25519");
        assertThatThrownBy(() -> KeyMaterialBootstrap.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                repositories,
                new KeyMaterialCreation(true),
                events))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RSA");
        assertThat(Files.exists(tempDir.resolve("security/orion.p12"))).isFalse();
    }

    private OrionKeyMaterial openLocal(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            KeyMaterialCreation creation) throws Exception {
        return KeyMaterialBootstrap.open(configuration, environment,
                repositories, creation, events);
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
                .setActive(new SigningKeyReferenceConfig("cluster-signing-v2", 1));
        return configuration;
    }
}
