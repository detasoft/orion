package pro.deta.orion.test;

import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialService;
import pro.deta.orion.keymaterial.LocalKeyMaterialContentStore;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.schema.config.OrionConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;

final class TestServerIdentityMaterial implements AutoCloseable {
    private static final String PASSWORD_ENV = "ORION_TEST_KEY_MATERIAL_PASSWORD";
    private static final String PASSWORD = "integration-test-password";

    private final OrionKeyMaterial material;
    private final KeyPair keyPair;

    private TestServerIdentityMaterial(OrionKeyMaterial material, KeyPair keyPair) {
        this.material = material;
        this.keyPair = keyPair;
    }

    static TestServerIdentityMaterial open(OrionConfiguration configuration) throws Exception {
        Path baseDirectory = Path.of(configuration.getBootstrap().getBaseDir());
        Files.createDirectories(baseDirectory);
        configuration.getBootstrap().getKeyMaterial().setLocation(
                baseDirectory.toRealPath().resolve("material.p12").toString());
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        OrionKeyMaterial material = OrionKeyMaterialFactory.open(
                configuration, Map.of(PASSWORD_ENV, PASSWORD));
        try {
            return new TestServerIdentityMaterial(material, readActiveKey(configuration));
        } catch (Exception failure) {
            material.close();
            throw failure;
        }
    }

    ServerIdentityCapability capability() {
        return material.serverIdentity();
    }

    KeyPair keyPair() {
        return keyPair;
    }

    @Override
    public void close() {
        material.close();
    }

    private static KeyPair readActiveKey(OrionConfiguration configuration) throws Exception {
        Path location = Path.of(configuration.getBootstrap().getBaseDir())
                .resolve(configuration.getBootstrap().getKeyMaterial().getLocation())
                .normalize();
        String alias = configuration.getBootstrap()
                .getKeyMaterial()
                .getServerSigning()
                .getActive()
                .getAlias();
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12(PASSWORD.toCharArray());
             KeyMaterialService service = KeyMaterialService.open(
                     new LocalKeyMaterialContentStore(location), options)) {
            return service.getKeyPair(alias);
        }
    }
}
