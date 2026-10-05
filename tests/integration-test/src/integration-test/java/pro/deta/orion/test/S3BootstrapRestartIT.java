package pro.deta.orion.test;

import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.config.ConfigurationFile;
import pro.deta.orion.OrionKeyMaterialFactory;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.fileapi.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.s3.S3NativeGitRepositoryFactory;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.PASSWORD_ENV;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.materialBytes;
import static pro.deta.orion.test.RemoteBootstrapTestSupport.runtimeComponent;

@Timeout(120)
class S3BootstrapRestartIT {
    private static final String SECRET_ID = "stateless-restart-secret";
    private static final String SECRET_VALUE = "persisted encrypted bootstrap credential";
    private static final String CONFIGURATION_REF = "refs/heads/bootstrap-config";
    private static final String MATERIAL_REF = "refs/heads/bootstrap-material";
    private static final String CONFIGURATION_PATH = "configuration/orion.xml";
    private static final String MATERIAL_PATH = "keys/server.p12";

    @TempDir
    Path tempDir;

    @Test
    void startsFromPreseededS3WithAnEmptyLocalDirectory() throws Exception {
        try (MinioS3TestServer s3 = MinioS3TestServer.start("orion-bootstrap-" + UUID.randomUUID())) {
            Map<String, String> environment = Map.of(
                    PASSWORD_ENV, "bootstrap-test-password", "S3_BOOTSTRAP_SECRET", s3.secretAccessKey());
            BootstrapConfiguration configuration = configuration(s3, tempDir);
            configuration.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
            InMemoryKeyMaterialContentStore materialStore = new InMemoryKeyMaterialContentStore();
            materialStore.write(materialBytes(configuration, environment), null);
            byte[] payload = "preseeded S3 server identity".getBytes(StandardCharsets.UTF_8);
            byte[] signature;
            String signingKeyId;
            byte[] xml;
            try (OrionKeyMaterial material = OrionKeyMaterialFactory.open(
                    configuration, environment, materialStore, false)) {
                signingKeyId = material.serverIdentity().activeKeyId();
                signature = material.serverIdentity().sign(payload);
                OrionDocument document = OrionDocument.withAccessControl(
                        ACLUtil.generateDefaultAccessControl("cold-start-test-password-hash"));
                ConfigurationSecrets secrets = new ConfigurationSecrets(() -> document,
                        material.configurationCipher());
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                OrionXml.write(secrets.createSystem(document, SECRET_ID, SECRET_VALUE.toCharArray()), output);
                xml = output.toByteArray();
            }
            byte[] material = materialStore.read().orElseThrow().bytes();
            Map<String, String> refs;
            try (NativeGitRepositoryProvider provider = S3NativeGitRepositoryFactory.repositories(
                    configuration.getStorage().getLocation(), configuration.getStorage().getEndpoint(),
                    configuration.getStorage().getAuth(), environment)) {
                NativeGitRepository repository = provider.create("orion").valueOrFailure("seed S3 bootstrap");
                repository.files().withAccess(CONFIGURATION_REF, "Seed bootstrap configuration",
                        GitCommitAuthor.EMPTY, access -> {
                            access.write(CONFIGURATION_PATH, xml);
                            access.apply();
                            return null;
                        });
                repository.files().withAccess(MATERIAL_REF, "Seed bootstrap key material",
                        GitCommitAuthor.EMPTY, access -> {
                            access.write(MATERIAL_PATH, material);
                            access.apply();
                            return null;
                        });
                refs = Map.copyOf(repository.refs());
            }

            assertThat(tempDir).isEmptyDirectory();
            try (BootstrapContext bootstrap = BootstrapContext.open(configuration, environment, false)) {
                assertThat(bootstrap.initialConfiguration()).isPresent();
                assertThat(bootstrap.serverIdentity().activeKeyId()).isEqualTo(signingKeyId);
                assertThat(bootstrap.serverIdentity().verify(signingKeyId, payload, signature)).isTrue();
                OrionComponent component = runtimeComponent(configuration, bootstrap);
                OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
                try {
                    assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                    lifecycle.waitForStarting();
                    OrionDocument loaded = OrionXml.read(new ByteArrayInputStream(component.orionAccessControlService()
                            .accessControlConfigurationFile().content()));
                    ConfigurationSecrets secrets = new ConfigurationSecrets(() -> loaded,
                            bootstrap.configurationCipher());
                    assertThat(secrets.resolveSystem(SECRET_ID)).isEqualTo(SECRET_VALUE.toCharArray());
                    NativeGitRepository repository = bootstrap.repositoryProvider().find("orion")
                            .valueOrFailure("loaded S3 bootstrap repository");
                    assertThat(repository.files().readBytes(MATERIAL_REF, MATERIAL_PATH)).isEqualTo(material);
                    assertThat(repository.refs()).containsEntry(MATERIAL_REF, refs.get(MATERIAL_REF));
                } finally {
                    lifecycle.shutdownApplication();
                    lifecycle.waitForShutdown();
                }
            }
        }
    }

    @Test
    void restartsFromS3WithAnEmptyLocalDirectoryAndCreationDisabled() throws Exception {
        try (MinioS3TestServer s3 = MinioS3TestServer.start("orion-bootstrap-" + UUID.randomUUID())) {
            Map<String, String> environment = Map.of(
                    PASSWORD_ENV, "bootstrap-test-password", "S3_BOOTSTRAP_SECRET", s3.secretAccessKey());
            byte[] payload = "stateless server identity".getBytes(StandardCharsets.UTF_8);
            byte[] signature;
            String signingKeyId;
            List<String> hostKeys = new ArrayList<>();
            byte[] savedConfiguration;
            byte[] savedMaterial;
            Map<String, String> savedRefs;

            Path firstRoot = Files.createDirectory(tempDir.resolve("first"));
            BootstrapConfiguration first = configuration(s3, firstRoot);
            first.getBootstrap().getAccessControl().setCreateDefaultIfMissing(true);
            try (BootstrapContext bootstrap = BootstrapContext.open(first, environment, true)) {
                OrionComponent component = runtimeComponent(first, bootstrap);
                OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
                try {
                    assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                    lifecycle.waitForStarting();
                    ConfigurationFile current = component.orionAccessControlService()
                            .accessControlConfigurationFile();
                    OrionDocument document = OrionXml.read(new ByteArrayInputStream(current.content()));
                    ConfigurationSecrets secrets = new ConfigurationSecrets(() -> document,
                            bootstrap.configurationCipher());
                    OrionDocument updated = secrets.createSystem(document, SECRET_ID, SECRET_VALUE.toCharArray());
                    component.configurationEditor().edit(current.revision().orElseThrow())
                            .update(ignored -> updated)
                            .apply("Persist stateless bootstrap secret", UserEmail.EMPTY);
                    savedConfiguration = component.orionAccessControlService()
                            .accessControlConfigurationFile().content();
                    assertThat(new String(savedConfiguration, StandardCharsets.UTF_8))
                            .contains(SECRET_ID).doesNotContain(SECRET_VALUE);
                    NativeGitRepository repository = bootstrap.repositoryProvider().find("orion")
                            .valueOrFailure("S3 bootstrap repository");
                    savedMaterial = repository.files().readBytes(MATERIAL_REF, MATERIAL_PATH);
                    savedRefs = Map.copyOf(repository.refs());
                    assertThat(savedRefs).containsKeys(CONFIGURATION_REF, MATERIAL_REF);
                    signingKeyId = bootstrap.serverIdentity().activeKeyId();
                    signature = bootstrap.serverIdentity().sign(payload);
                    for (KeyPair pair : bootstrap.sshHostKeys().keyPairs()) {
                        hostKeys.add(PublicKeyEntry.toString(pair.getPublic()));
                    }
                    assertThat(hostKeys).isNotEmpty();
                } finally {
                    lifecycle.shutdownApplication();
                    lifecycle.waitForShutdown();
                }
            }

            try (Stream<Path> paths = Files.walk(firstRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            assertThat(firstRoot).doesNotExist();
            Path freshRoot = Files.createDirectory(tempDir.resolve("restarted"));
            assertThat(freshRoot).isEmptyDirectory();
            BootstrapConfiguration restarted = configuration(s3, freshRoot);
            restarted.getBootstrap().getAccessControl().setCreateDefaultIfMissing(false);
            try (BootstrapContext bootstrap = BootstrapContext.open(restarted, environment, false)) {
                assertThat(bootstrap.initialConfiguration()).isPresent();
                assertThat(bootstrap.serverIdentity().activeKeyId()).isEqualTo(signingKeyId);
                assertThat(bootstrap.serverIdentity().verify(signingKeyId, payload, signature)).isTrue();
                assertThat(bootstrap.sshHostKeys().keyPairs())
                        .extracting(pair -> PublicKeyEntry.toString(pair.getPublic()))
                        .containsExactlyElementsOf(hostKeys);

                OrionComponent component = runtimeComponent(restarted, bootstrap);
                OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
                try {
                    assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
                    lifecycle.waitForStarting();
                    byte[] loadedConfiguration = component.orionAccessControlService()
                            .accessControlConfigurationFile().content();
                    assertThat(loadedConfiguration).isEqualTo(savedConfiguration);
                    OrionDocument loaded = OrionXml.read(new ByteArrayInputStream(loadedConfiguration));
                    ConfigurationSecrets secrets = new ConfigurationSecrets(() -> loaded,
                            bootstrap.configurationCipher());
                    assertThat(secrets.resolveSystem(SECRET_ID)).isEqualTo(SECRET_VALUE.toCharArray());
                    NativeGitRepository repository = bootstrap.repositoryProvider().find("orion")
                            .valueOrFailure("reopened S3 bootstrap repository");
                    assertThat(repository.files().readBytes(MATERIAL_REF, MATERIAL_PATH)).isEqualTo(savedMaterial);
                    assertThat(repository.refs()).isEqualTo(savedRefs);
                } finally {
                    lifecycle.shutdownApplication();
                    lifecycle.waitForShutdown();
                }
            }
        }
    }

    private static BootstrapConfiguration configuration(MinioS3TestServer s3, Path localRoot) throws Exception {
        BootstrapConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(localRoot,
                config -> config.getTransport().getSsh().setEnabled(true));
        configuration.getStorage().setLocation("s3://" + s3.bucketName() + "/bootstrap");
        configuration.getStorage().setEndpoint(s3.endpoint());
        configuration.getStorage().setAuth(Map.of("accessKeyId", s3.accessKeyId(),
                "secretAccessKey", "env:S3_BOOTSTRAP_SECRET"));
        configuration.getBootstrap().getAccessControl().setRef(CONFIGURATION_REF);
        configuration.getBootstrap().getAccessControl().setPath(CONFIGURATION_PATH);
        configuration.getBootstrap().getKeyMaterial().setRef(MATERIAL_REF);
        configuration.getBootstrap().getKeyMaterial().setPath(MATERIAL_PATH);
        configuration.getBootstrap().getKeyMaterial().setPassword("env:" + PASSWORD_ENV);
        return configuration;
    }
}
