package pro.deta.orion.test;

import pro.deta.orion.util.VcChangeDescription;

import pro.deta.orion.util.VcRevision;

import pro.deta.orion.keymaterial.KeyMaterialOperation;

import pro.deta.orion.keymaterial.KeyMaterialCodec;

import pro.deta.orion.keymaterial.KeyMaterialRepository;

import pro.deta.orion.event.OrionEventManager;

import pro.deta.orion.KeyMaterialBootstrap;

import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialId;
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

    private final pro.deta.orion.test.util.OrionTestRuntime runtime;
    private final OrionKeyMaterial material;
    private final KeyPair keyPair;
    private final KeyPairProvider sshHostKeys;

    private TestServerIdentityMaterial(
            pro.deta.orion.test.util.OrionTestRuntime runtime,
            OrionKeyMaterial material,
            KeyPair keyPair,
            KeyPairProvider sshHostKeys) {
        this.runtime = runtime;
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
        pro.deta.orion.test.util.OrionTestRuntime runtime =
                new pro.deta.orion.test.util.OrionTestRuntime();
        Path path = Path.of(configuration.getBootstrap().getKeyMaterial().getLocation());
        if (!Files.exists(path)) {
            Files.write(
                    path,
                    pro.deta.orion.test.util.OrionTestRuntime.pkcs12Content(
                            PASSWORD.toCharArray()));
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(
                        path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        }
        BlobStorage store = KeyMaterialResourceResolver.standard(Map.of())
                .resolveSource(configuration.getBootstrap().getKeyMaterial().getLocation(),
                                runtime.events());
        OrionKeyMaterial material = openMaterial(configuration, Map.of(PASSWORD_ENV, PASSWORD), store);
        try {
            if (!material.contains(
                    KeyMaterialBootstrap.signingMaterial(
                                    configuration.getBootstrap().getKeyMaterial())
                            .active())) {
                try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
                    modification.generateKey(KeyMaterialBootstrap.signingMaterial(
                            configuration.getBootstrap().getKeyMaterial()).active().alias(),
                            Set.of(KeyMaterialPurpose.SERVER_SIGNING), KeyMaterialAlgorithm.RSA, KeyMaterialConstants.RSA_KEY_SIZE_BITS);
                    modification.commit(VcChangeDescription.EMPTY);
                }
            }
            return new TestServerIdentityMaterial(
                    runtime,
                    material,
                    material.read(KeyMaterialBootstrap.signingMaterial(
                            configuration.getBootstrap().getKeyMaterial()).active()).keyPair(),
                    hostKeys(material));
        } catch (Exception failure) {
            material.close();
            runtime.close();
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
        KeyMaterialId rsa = new KeyMaterialId("ssh-host-rsa-v1", 1);
        KeyMaterialId ec = new KeyMaterialId("ssh-host-ec-v1", 1);
        try (KeyMaterialOperation modification = material.open(new VcRevision.Stored(material.revision()))) {
            modification.generateKey("ssh-host-rsa-v1", Set.of(KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.RSA, KeyMaterialConstants.RSA_KEY_SIZE_BITS);
            modification.generateKey("ssh-host-ec-v1", Set.of(KeyMaterialPurpose.SSH_HOST), KeyMaterialAlgorithm.EC, KeyMaterialConstants.EC_KEY_SIZE_BITS);
            modification.commit(VcChangeDescription.EMPTY);
        }
        return session -> List.of(material.read(ec).keyPair(), material.read(rsa).keyPair());
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
        runtime.close();
    }
}
