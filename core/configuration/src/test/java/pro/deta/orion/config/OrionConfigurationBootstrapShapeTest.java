package pro.deta.orion.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrionConfigurationBootstrapShapeTest {
    @TempDir
    private Path tempDir;

    @Test
    void parsesBootstrapStorageAndTransportShape() throws Exception {
        Path configFile = tempDir.resolve("orion.yml");
        Files.writeString(configFile, """
                bootstrap:
                  baseDir: /tmp/orion
                  workDir: work
                  threadPoolSize: 7
                  jgit:
                    hostname: orion-test
                  accessControl:
                    location: local:orion
                    branch: master
                    paths:
                      - acl/orion.xml
                    createDefaultIfMissing: false
                    auth:
                      username: acl
                storage:
                  location: file:/tmp/orion/repositories/
                  createOnPush: false
                transport:
                  defaultAddress: localhost
                  git:
                    enabled: false
                    port: 9418
                  ssh:
                    enabled: false
                    port: 8022
                  http:
                    enabled: false
                    port: 8000
                  https:
                    enabled: false
                    port: 8443
                    acme:
                      enabled: true
                      directoryUrl: acme://letsencrypt.org/staging
                      accountEmail: admin@example.test
                      domains:
                        - example.test
                        - www.example.test
                      organization: ORION
                      accountKeyPath: keys/account.keypair
                      domainKeyPath: keys/domain.keypair
                      certificatePath: certs/nginx.pem
                      authorizationTimeoutSeconds: 30
                      orderTimeoutSeconds: 40
                      agreeToTermsOfService: true
                      allowRequestedDomains: true
                """);

        OrionConfiguration configuration = new LocationConfigurationProvider()
                .configurationLookup(configFile.toString());

        assertEquals("/tmp/orion", configuration.getBootstrap().getBaseDir());
        assertEquals("work", configuration.getBootstrap().getWorkDir());
        assertEquals(7, configuration.getBootstrap().getThreadPoolSize());
        assertEquals("orion-test", configuration.getBootstrap().getJgit().getHostname());
        assertEquals("local:orion", configuration.getBootstrap().getAccessControl().getLocation());
        assertEquals("acl/orion.xml", configuration.getBootstrap().getAccessControl().primaryPath());
        assertFalse(configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing());
        assertEquals("acl", configuration.getBootstrap().getAccessControl().getAuth().get("username"));
        assertEquals("file:/tmp/orion/repositories/", configuration.getStorage().getLocation());
        assertFalse(configuration.getStorage().isCreateOnPush());
        assertEquals(8000, configuration.getTransport().getHttp().getPort());
        assertFalse(configuration.getTransport().getHttp().isEnabled());
        assertEquals("acme://letsencrypt.org/staging", configuration.getTransport().getHttps().getAcme().getDirectoryUrl());
        assertEquals("admin@example.test", configuration.getTransport().getHttps().getAcme().getAccountEmail());
        assertEquals("example.test", configuration.getTransport().getHttps().getAcme().getDomains().getFirst());
        assertEquals("www.example.test", configuration.getTransport().getHttps().getAcme().getDomains().get(1));
        assertEquals("ORION", configuration.getTransport().getHttps().getAcme().getOrganization());
        assertEquals("keys/account.keypair", configuration.getTransport().getHttps().getAcme().getAccountKeyPath());
        assertEquals("keys/domain.keypair", configuration.getTransport().getHttps().getAcme().getDomainKeyPath());
        assertEquals("certs/nginx.pem", configuration.getTransport().getHttps().getAcme().getCertificatePath());
        assertEquals(30, configuration.getTransport().getHttps().getAcme().getAuthorizationTimeoutSeconds());
        assertEquals(40, configuration.getTransport().getHttps().getAcme().getOrderTimeoutSeconds());
        assertEquals(true, configuration.getTransport().getHttps().getAcme().isAgreeToTermsOfService());
        assertEquals(true, configuration.getTransport().getHttps().getAcme().isAllowRequestedDomains());
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
