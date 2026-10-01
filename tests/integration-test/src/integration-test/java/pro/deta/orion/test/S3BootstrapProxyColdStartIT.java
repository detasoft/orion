package pro.deta.orion.test;

import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import pro.deta.orion.git.s3.S3NativeGitRepositoryFactory;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.bootstrap.config.OrionConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.PASSWORD_ENV;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.configureSources;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.materialBytes;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.runtimeComponent;

@Timeout(120)
class S3BootstrapProxyColdStartIT {
    private static final String REF = "refs/heads/bootstrap";
    private static final String CONFIGURATION_PATH = "configuration/orion.xml";
    private static final String MATERIAL_PATH = "keys/server.p12";
    private static final String SECRET_ID = "proxy-cold-start-secret";
    private static final String SECRET_VALUE = "remote encrypted bootstrap credential";

    @TempDir
    Path tempDir;

    @Test
    void hydratesS3ProxyCacheFromRemoteBootstrapWithAnEmptyLocalDirectory() throws Exception {
        Path localRoot = Files.createDirectory(tempDir.resolve("target"));
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(localRoot);
        OrionConfiguration upstreamConfiguration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("upstream"));
        try (MinioS3TestServer s3 = MinioS3TestServer.start("orion-proxy-" + UUID.randomUUID())) {
            configuration.getStorage().setLocation("s3://" + s3.bucketName() + "/proxy-cache");
            configuration.getStorage().setEndpoint(s3.endpoint());
            configuration.getStorage().setAuth(Map.of("accessKeyId", s3.accessKeyId(),
                    "secretAccessKey", "env:S3_BOOTSTRAP_SECRET"));
            configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
            configuration.getBootstrap().getAccessControl().setRef(REF);
            configuration.getBootstrap().getAccessControl().setPath(CONFIGURATION_PATH);
            configuration.getBootstrap().getKeyMaterial().setRef(REF);
            configuration.getBootstrap().getKeyMaterial().setPath(MATERIAL_PATH);
            configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
            Map<String, String> environment = new HashMap<>();
            environment.put("S3_BOOTSTRAP_SECRET", s3.secretAccessKey());
            String cacheName;
            byte[] material;
            byte[] loadedConfiguration;
            byte[] marker = "upstream change after cold start".getBytes(StandardCharsets.UTF_8);
            String refreshedRevision;
            try (RuntimeHttpTestSupport.StartedOrion upstream = RuntimeHttpTestSupport.start(upstreamConfiguration)) {
                environment.putAll(configureSources(tempDir, configuration, upstream, "http"));
                InMemoryKeyMaterialContentStore materialStore = new InMemoryKeyMaterialContentStore();
                materialStore.write(materialBytes(configuration, environment), null);
                byte[] payload = "preseeded proxy server identity".getBytes(StandardCharsets.UTF_8);
                String signingKeyId;
                byte[] signature;
                byte[] xml;
                try (OrionKeyMaterial seeded = OrionKeyMaterialFactory.open(
                        configuration, environment, materialStore, false)) {
                    signingKeyId = seeded.serverIdentity().activeKeyId();
                    signature = seeded.serverIdentity().sign(payload);
                    OrionDocument document = OrionDocument.withAccessControl(
                            ACLUtil.generateDefaultAccessControl("proxy-cold-start-password-hash"));
                    ConfigurationSecrets secrets = new ConfigurationSecrets(() -> document,
                            seeded.configurationCipher());
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    OrionXml.write(secrets.createSystem(document, SECRET_ID, SECRET_VALUE.toCharArray()), output);
                    xml = output.toByteArray();
                }
                material = materialStore.read().orElseThrow().bytes();
                NativeGitRepository repository = upstream.repositoryProvider().create("bootstrap-inputs")
                        .valueOrFailure("seed remote bootstrap repository");
                repository.files().withAccess(REF, "Seed remote bootstrap inputs", GitCommitAuthor.EMPTY, access -> {
                    access.write(CONFIGURATION_PATH, xml);
                    access.write(MATERIAL_PATH, material);
                    access.apply();
                    return null;
                });
                try (NativeGitRepositoryProvider empty = S3NativeGitRepositoryFactory.repositories(
                        configuration.getStorage().getLocation(), configuration.getStorage().getEndpoint(),
                        configuration.getStorage().getAuth(), environment)) {
                    assertThat(empty.repositoryNames()).isEmpty();
                }
                assertThat(localRoot).isEmptyDirectory();
                try (BootstrapContext bootstrap = BootstrapContext.open(configuration, environment, false)) {
                    assertThat(bootstrap.initialConfiguration().orElseThrow().content()).isEqualTo(xml);
                    assertThat(bootstrap.serverIdentity().activeKeyId()).isEqualTo(signingKeyId);
                    assertThat(bootstrap.serverIdentity().verify(signingKeyId, payload, signature)).isTrue();
                    cacheName = bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.CONFIGURATION_SOURCE).orElseThrow();
                    assertThat(bootstrap.repositoryFactory()
                            .bootstrapRepositoryName(NativeGitRepositoryFactory.MATERIAL_SOURCE)).contains(cacheName);
                    OrionComponent component = runtimeComponent(configuration, bootstrap);
                    OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
                    try {
                        assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                        lifecycle.waitForStarting();
                        loadedConfiguration = component.orionAccessControlService()
                                .accessControlConfigurationFile().content();
                        OrionDocument loaded = OrionXml.read(new ByteArrayInputStream(loadedConfiguration));
                        ConfigurationSecrets secrets = new ConfigurationSecrets(() -> loaded,
                                bootstrap.configurationCipher());
                        assertThat(secrets.resolveSystem(SECRET_ID)).isEqualTo(SECRET_VALUE.toCharArray());
                        assertThat(bootstrap.repositoryProvider().repositoryNames()).doesNotContain(cacheName);
                        NativeGitRepository cache = bootstrap.repositoryProvider().openForRead(cacheName)
                                .valueOrFailure("hydrated S3 proxy cache");
                        assertThat(cache.files().readBytes(REF, MATERIAL_PATH)).isEqualTo(material);
                        String initialRevision = cache.refs().get(REF);
                        repository.files().withAccess(REF, "Change upstream after cold start", GitCommitAuthor.EMPTY,
                                access -> {
                                    access.write("marker.txt", marker);
                                    access.apply();
                                    return null;
                                });
                        refreshedRevision = repository.refs().get(REF);
                        assertThat(refreshedRevision).isNotEqualTo(initialRevision);
                        bootstrap.repositoryProvider().openForRead(cacheName).valueOrFailure("refresh S3 proxy cache");
                        assertThat(cache.files().readBytes(REF, "marker.txt")).isEqualTo(marker);
                        assertThat(cache.refs()).containsEntry(REF, refreshedRevision);
                    } finally {
                        lifecycle.shutdownApplication();
                        lifecycle.waitForShutdown();
                    }
                }
            }
            try (NativeGitRepositoryProvider reopened = S3NativeGitRepositoryFactory.repositories(
                    configuration.getStorage().getLocation(), configuration.getStorage().getEndpoint(),
                    configuration.getStorage().getAuth(), environment)) {
                NativeGitRepository persisted = reopened.find(cacheName).valueOrFailure("persisted S3 proxy cache");
                assertThat(persisted.files().readBytes(REF, CONFIGURATION_PATH)).isEqualTo(loadedConfiguration);
                assertThat(persisted.files().readBytes(REF, MATERIAL_PATH)).isEqualTo(material);
                assertThat(persisted.files().readBytes(REF, "marker.txt")).isEqualTo(marker);
                assertThat(persisted.refs()).containsEntry(REF, refreshedRevision);
            }
        }
    }
}
