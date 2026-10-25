package pro.deta.orion.test;

import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import java.util.Set;
import pro.deta.orion.keymaterial.KeyMaterialCreation;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
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
    private final KeyPairProvider sshHostKeys;

    private TestServerIdentityMaterial(
            OrionKeyMaterial material,
            KeyPair keyPair,
            KeyPairProvider sshHostKeys) {
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
                    hostKeys(material));
        } catch (Exception failure) {
            material.close();
            throw failure;
        }
    }

    static KeyPairProvider hostKeys(OrionKeyMaterial material) throws Exception {
        KeyMaterialDescriptor rsa = new KeyMaterialDescriptor(
                "ssh-host-rsa-v1", Set.of(KeyMaterialPurpose.SSH_HOST),
                KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor ec = new KeyMaterialDescriptor(
                "ssh-host-ec-v1", Set.of(KeyMaterialPurpose.SSH_HOST),
                KeyMaterialAlgorithm.EC, 1);
        material.withModification(modification -> {
            modification.generateKey(rsa, KeyMaterialConstants.RSA_KEY_SIZE_BITS);
            modification.generateKey(ec, KeyMaterialConstants.EC_KEY_SIZE_BITS);
            modification.apply();
        });
        return session -> List.of(material.keyPair(ec), material.keyPair(rsa));
    }

    OrionKeyMaterial material() {
        return material;
    }

    KeyPair keyPair() {
        return keyPair;
    }

    KeyPairProvider sshHostKeys() {
        return sshHostKeys;
    }

    @Override
    public void close() {
        material.close();
    }
}
