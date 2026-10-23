package pro.deta.orion.test;

import pro.deta.orion.keymaterial.KeyMaterialCreation;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SshHostKeyCapability;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

final class TestServerIdentityMaterial implements AutoCloseable {
    private static final String PASSWORD_ENV = "ORION_TEST_KEY_MATERIAL_PASSWORD";
    private static final String PASSWORD = "integration-test-password";

    private final OrionKeyMaterial material;
    private final KeyPair keyPair;
    private final SshHostKeyCapability sshHostKeys;

    private TestServerIdentityMaterial(
            OrionKeyMaterial material,
            KeyPair keyPair,
            SshHostKeyCapability sshHostKeys) {
        this.material = material;
        this.keyPair = keyPair;
        this.sshHostKeys = sshHostKeys;
    }

    static TestServerIdentityMaterial open(BootstrapConfiguration configuration) throws Exception {
        Path baseDirectory = Path.of(configuration.getBootstrap().getBaseDir());
        Files.createDirectories(baseDirectory);
        configuration.getBootstrap().getKeyMaterial().setLocation(
                baseDirectory.toRealPath().resolve("material.p12").toString());
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        OrionKeyMaterial material = OrionKeyMaterialFactory.open(
                configuration, Map.of(PASSWORD_ENV, PASSWORD), new KeyMaterialCreation(true));
        try {
            return new TestServerIdentityMaterial(
                    material,
                    material.keyPair(OrionKeyMaterialFactory.signingMaterial(
                            configuration.getBootstrap().getKeyMaterial()).active()),
                    material.sshHostKeys(List.of()));
        } catch (Exception failure) {
            material.close();
            throw failure;
        }
    }

    OrionKeyMaterial material() {
        return material;
    }

    KeyPair keyPair() {
        return keyPair;
    }

    SshHostKeyCapability sshHostKeys() {
        return sshHostKeys;
    }

    @Override
    public void close() {
        material.close();
    }
}
