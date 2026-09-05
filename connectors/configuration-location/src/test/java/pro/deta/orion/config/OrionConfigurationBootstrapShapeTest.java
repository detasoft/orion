package pro.deta.orion.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.schema.config.OrionConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrionConfigurationBootstrapShapeTest {
    @TempDir
    private Path tempDir;

    @Test
    void keyMaterialSourceDefaultsToTheBootstrapRepository() {
        OrionConfiguration configuration = new OrionConfiguration();

        assertEquals("local:orion", configuration.getBootstrap().getKeyMaterial().getLocation());
        assertEquals("refs/heads/main", configuration.getBootstrap().getKeyMaterial().selectedRef());
        assertEquals("material.p12", configuration.getBootstrap().getKeyMaterial().getPath());
    }

    @Test
    void partialKeyMaterialSectionRetainsSourceDefaults() throws Exception {
        Path configFile = tempDir.resolve("partial-material.yml");
        Files.writeString(configFile, """
                bootstrap:
                  keyMaterial:
                    password: env:TEST_MATERIAL_PASSWORD
                """);

        OrionConfiguration configuration = new LocationConfigurationProvider()
                .configurationLookup(configFile.toString());

        assertEquals("local:orion", configuration.getBootstrap().getKeyMaterial().getLocation());
        assertEquals("refs/heads/main", configuration.getBootstrap().getKeyMaterial().selectedRef());
        assertEquals("material.p12", configuration.getBootstrap().getKeyMaterial().getPath());
    }

    @Test
    void parsesBootstrapStorageAndTransportShape() throws Exception {
        Path configFile = tempDir.resolve("orion.yml");
        Files.writeString(configFile, """
                bootstrap:
                  baseDir: /tmp/orion
                  workDir: work
                  threadPoolSize: 7
                  accessControl:
                    location: git+https://config.example/orion.git
                    ref: refs/heads/configuration
                    path: acl/orion.xml
                    createDefaultIfMissing: false
                    auth:
                      credentialKind: http-bearer
                      credential: env:ORION_CONFIG_TOKEN
                  keyMaterial:
                    location: git+ssh://material.example/orion.git
                    ref: refs/heads/keys
                    path: stores/material.p12
                    password: env:ORION_KEY_MATERIAL_PASSWORD
                    auth:
                      credentialKind: ssh-private-key
                      credential: file:/run/secrets/orion-material-key
                    clusterId: orion-cluster
                    serverSigning:
                      algorithm: RSA
                      active:
                        alias: server-signing-v2
                        version: 2
                      verification:
                        - alias: server-signing-v1
                          version: 1
                storage:
                  location: file:/tmp/orion/repositories/
                  createOnPush: false
                transport:
                  defaultAddress: localhost
                  git:
                    enabled: false
                    port: 9418
                    packfileUri:
                      baseUri: https://git.example/r
                      trustedProxyAddresses:
                        - 127.0.0.1
                  ssh:
                    enabled: false
                    port: 8022
                  http:
                    enabled: false
                    port: 8000
                """);

        OrionConfiguration configuration = new LocationConfigurationProvider()
                .configurationLookup(configFile.toString());

        assertEquals("/tmp/orion", configuration.getBootstrap().getBaseDir());
        assertEquals("work", configuration.getBootstrap().getWorkDir());
        assertEquals(7, configuration.getBootstrap().getThreadPoolSize());
        assertEquals(
                "git+https://config.example/orion.git",
                configuration.getBootstrap().getAccessControl().getLocation());
        assertEquals(
                "refs/heads/configuration",
                configuration.getBootstrap().getAccessControl().selectedRef());
        assertEquals("acl/orion.xml", configuration.getBootstrap().getAccessControl().getPath());
        assertFalse(configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing());
        assertEquals(
                "env:ORION_CONFIG_TOKEN",
                configuration.getBootstrap().getAccessControl().getAuth().get("credential"));
        assertEquals(
                "git+ssh://material.example/orion.git",
                configuration.getBootstrap().getKeyMaterial().getLocation());
        assertEquals("refs/heads/keys", configuration.getBootstrap().getKeyMaterial().selectedRef());
        assertEquals("stores/material.p12", configuration.getBootstrap().getKeyMaterial().getPath());
        assertEquals(
                "env:ORION_KEY_MATERIAL_PASSWORD",
                configuration.getBootstrap().getKeyMaterial().getPassword());
        assertEquals(
                "file:/run/secrets/orion-material-key",
                configuration.getBootstrap().getKeyMaterial().getAuth().get("credential"));
        assertEquals("orion-cluster", configuration.getBootstrap().getKeyMaterial().getClusterId());
        assertEquals(
                "server-signing-v2",
                configuration.getBootstrap().getKeyMaterial().getServerSigning().getActive().getAlias());
        assertEquals(
                2,
                configuration.getBootstrap().getKeyMaterial().getServerSigning().getActive().getVersion());
        assertEquals(
                "server-signing-v1",
                configuration.getBootstrap()
                        .getKeyMaterial()
                        .getServerSigning()
                        .getVerification()
                        .getFirst()
                        .getAlias());
        assertEquals("file:/tmp/orion/repositories/", configuration.getStorage().getLocation());
        assertFalse(configuration.getStorage().isCreateOnPush());
        assertEquals(8000, configuration.getTransport().getHttp().getPort());
        assertFalse(configuration.getTransport().getHttp().isEnabled());
        assertEquals(
                "https://git.example/r",
                configuration.getTransport().getGit().getPackfileUri().getBaseUri());
        assertEquals(
                "127.0.0.1",
                configuration.getTransport()
                        .getGit()
                        .getPackfileUri()
                        .getTrustedProxyAddresses()
                        .getFirst());
    }

    @Test
    void legacyHttpsBootstrapShapeIsRejected() throws Exception {
        Path configFile = tempDir.resolve("legacy-https.yml");
        Files.writeString(configFile, """
                transport:
                  https:
                    enabled: true
                    port: 8443
                    ksystore:
                      path: certs/server.pem
                    acme:
                      accountKeyPath: keys/account.keypair
                      domainKeyPath: keys/domain.keypair
                      certificatePath: certs/nginx.pem
                """);

        assertThrows(RuntimeException.class, () -> new LocationConfigurationProvider()
                .configurationLookup(configFile.toString()));
    }

    @Test
    void oldTopLevelShapeIsRejected() throws Exception {
        Path configFile = tempDir.resolve("old.yml");
        Files.writeString(configFile, """
                baseDir: /tmp/orion
                git:
                  storagePath: repos
                accessControl:
                  url: local:orion
                transports:
                  defaultAddress: localhost
                """);

        assertThrows(RuntimeException.class, () -> new LocationConfigurationProvider()
                .configurationLookup(configFile.toString()));
    }

    @Test
    void explicitConfigurationLocationIsUsed() throws Exception {
        Path configFile = tempDir.resolve("explicit.yml");
        Files.writeString(configFile, """
                bootstrap:
                  baseDir: /tmp/explicit-orion
                """);

        OrionConfiguration configuration = new LocationConfigurationProvider(configFile.toString())
                .readConfiguration();

        assertEquals("/tmp/explicit-orion", configuration.getBootstrap().getBaseDir());
    }

    @Test
    void explicitMissingConfigurationLocationIsRejected() {
        Path configFile = tempDir.resolve("missing.yml");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new LocationConfigurationProvider(configFile.toString()).readConfiguration());

        assertEquals("Configuration location not found or unsupported: " + configFile, error.getMessage());
    }
}
