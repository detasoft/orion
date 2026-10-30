package pro.deta.orion.test;

import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;

import pro.deta.orion.event.OrionEventManager;

import pro.deta.orion.KeyMaterialBootstrap;

import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import java.util.Set;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import pro.deta.orion.storage.BlobStorage;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.KeyMaterialResourceResolver;
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
        BlobStorage store = KeyMaterialResourceResolver.standard(Map.of())
                .resolveSource(configuration.getBootstrap().getKeyMaterial().getLocation());
        OrionKeyMaterial material = openMaterial(configuration, Map.of(PASSWORD_ENV, PASSWORD), store);
        try {
            if (!material.hasDurableSnapshot()) {
                material.withModification(material.revision(), modification -> {
                    modification.generateKey(KeyMaterialBootstrap.signingMaterial(
                            configuration.getBootstrap().getKeyMaterial()).active(),
                            KeyMaterialConstants.RSA_KEY_SIZE_BITS);
                    modification.apply();
                });
            }
            return new TestServerIdentityMaterial(
                    material,
                    material.read(KeyMaterialBootstrap.signingMaterial(
                            configuration.getBootstrap().getKeyMaterial()).active().id()).keyPair(),
                    hostKeys(material));
        } catch (Exception failure) {
            material.close();
            throw failure;
        }
    }

    static OrionKeyMaterial openMaterial(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            BlobStorage store) throws Exception {
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(environment);
        try (KeyMaterialOptions options = resolver.pkcs12Options(
                configuration.getBootstrap().getKeyMaterial().getPassword())) {
            return OrionKeyMaterial.open(new KeyMaterialRepository(
                    store,
                    new KeyMaterialCodec(options),
                    new OrionEventManager()));
        }
    }

    static KeyPairProvider hostKeys(OrionKeyMaterial material) throws Exception {
        KeyMaterialDescriptor rsa = new KeyMaterialDescriptor(
                "ssh-host-rsa-v1", Set.of(KeyMaterialPurpose.SSH_HOST),
                KeyMaterialAlgorithm.RSA, 1);
        KeyMaterialDescriptor ec = new KeyMaterialDescriptor(
                "ssh-host-ec-v1", Set.of(KeyMaterialPurpose.SSH_HOST),
                KeyMaterialAlgorithm.EC, 1);
        material.withModification(material.revision(), modification -> {
            modification.generateKey(rsa, KeyMaterialConstants.RSA_KEY_SIZE_BITS);
            modification.generateKey(ec, KeyMaterialConstants.EC_KEY_SIZE_BITS);
            modification.apply();
        });
        return session -> List.of(material.read(ec.id()).keyPair(), material.read(rsa.id()).keyPair());
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
