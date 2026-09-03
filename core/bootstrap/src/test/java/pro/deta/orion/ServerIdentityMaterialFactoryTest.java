package pro.deta.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.keymaterial.ServerIdentityMaterial;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.SigningKeyReferenceConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerIdentityMaterialFactoryTest {
    private static final String PASSWORD_ENV = "ORION_TEST_KEY_MATERIAL_PASSWORD";

    @TempDir
    private Path tempDir;

    @Test
    void opensConfiguredRelativeStoreAndReloadsRetainedIdentity() throws Exception {
        OrionConfiguration configuration = configuration();
        byte[] payload = "jwt-input".getBytes(StandardCharsets.UTF_8);
        byte[] signature;
        try (ServerIdentityMaterial identity = ServerIdentityMaterialFactory.open(
                configuration, Map.of(PASSWORD_ENV, "test-password"))) {
            signature = identity.sign(payload);
            assertThat(identity.activeKeyId()).isEqualTo("cluster-signing-v2");
        }

        configuration.getBootstrap().getKeyMaterial().setCreateIfMissing(false);
        try (ServerIdentityMaterial reloaded = ServerIdentityMaterialFactory.open(
                configuration, Map.of(PASSWORD_ENV, "test-password"))) {
            assertThat(reloaded.verify("cluster-signing-v2", payload, signature)).isTrue();
        }

        assertThat(Files.isRegularFile(tempDir.resolve("security/orion.p12"))).isTrue();
    }

    @Test
    void resolvesRelativeStoreBelowEnvironmentBaseDirectory() throws Exception {
        OrionConfiguration configuration = configuration();
        configuration.getBootstrap().setBaseDir("env:ORION_TEST_ROOT/material-home");

        try (ServerIdentityMaterial ignored = ServerIdentityMaterialFactory.open(
                configuration,
                Map.of(
                        "ORION_TEST_ROOT", tempDir.toString(),
                        PASSWORD_ENV, "test-password"))) {
            // Opening the identity initializes the configured store.
        }

        assertThat(Files.isRegularFile(tempDir.resolve("material-home/security/orion.p12"))).isTrue();
    }

    @Test
    void resolvesConfiguredRetainedAliases() throws Exception {
        OrionConfiguration configuration = configuration();
        try (ServerIdentityMaterial ignored = ServerIdentityMaterialFactory.open(
                configuration, Map.of(PASSWORD_ENV, "test-password"))) {
            // Initialize the active alias in a new store.
        }
        configuration.getBootstrap().getKeyMaterial().setCreateIfMissing(false);
        configuration.getBootstrap()
                .getKeyMaterial()
                .getServerSigning()
                .setVerification(List.of(new SigningKeyReferenceConfig("missing-retained", 1)));

        assertThatThrownBy(() -> ServerIdentityMaterialFactory.open(
                configuration, Map.of(PASSWORD_ENV, "test-password")))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("missing-retained");
    }

    @Test
    void missingProtectedPasswordFailsWithoutCreatingStore() {
        OrionConfiguration configuration = configuration();

        assertThatThrownBy(() -> ServerIdentityMaterialFactory.open(configuration, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PASSWORD_ENV);
        assertThat(Files.exists(tempDir.resolve("security/orion.p12"))).isFalse();
    }

    @Test
    void opensIdentityFromAlreadyResolvedContentStore() throws Exception {
        OrionConfiguration configuration = configuration();
        configuration.getBootstrap().getKeyMaterial().setLocation("git+https://example.test/private.git");
        InMemoryKeyMaterialContentStore store = new InMemoryKeyMaterialContentStore();

        try (ServerIdentityMaterial identity = ServerIdentityMaterialFactory.open(
                configuration,
                Map.of(PASSWORD_ENV, "test-password"),
                store)) {
            assertThat(identity.activeKeyId()).isEqualTo("cluster-signing-v2");
        }

        assertThat(store.read()).isPresent();
    }

    private OrionConfiguration configuration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.toString());
        configuration.getBootstrap().getKeyMaterial().setLocation("security/orion.p12");
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        configuration.getBootstrap().getKeyMaterial().setCreateIfMissing(true);
        configuration.getBootstrap().getKeyMaterial().setClusterId("test-cluster");
        configuration.getBootstrap()
                .getKeyMaterial()
                .getServerSigning()
                .setActive(new SigningKeyReferenceConfig("cluster-signing-v2", 2));
        return configuration;
    }
}
