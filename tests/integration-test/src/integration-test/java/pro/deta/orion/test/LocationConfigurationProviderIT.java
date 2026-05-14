package pro.deta.orion.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.LocationConfigurationProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.test.integration.git.GitHttpTestServer;
import pro.deta.orion.test.integration.git.GitRepositoryFixture;
import pro.deta.orion.test.integration.git.GitSshTestServer;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;
import pro.deta.orion.util.KeyUtils;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocationConfigurationProviderIT {
    private static final String BRANCH = "master";
    private static final String CONFIG_FILE = "config/orion.yml";

    @TempDir
    Path tempDir;

    @Test
    void readsConfigurationFromGitHttpRepository() throws Exception {
        Path repositoriesRoot = tempDir.resolve("git-http");
        seedConfigurationRepository(
                repositoriesRoot.resolve("orion-config.git"),
                tempDir.resolve("git-http-seed"),
                "/tmp/orion-git-http-it",
                "git-http-it.xml",
                41080);

        try (GitHttpTestServer gitServer = GitHttpTestServer.start(repositoriesRoot)) {
            OrionConfiguration configuration = new LocationConfigurationProvider(
                    "git+" + gitServer.repositoryUrl("orion-config.git")
                            + "?branch=" + value(BRANCH)
                            + "&path=" + value(CONFIG_FILE))
                    .readConfiguration();

            assertConfiguration(configuration, "/tmp/orion-git-http-it", "git-http-it.xml", 41080);
        }
    }

    @Test
    void readsConfigurationFromGitSshRepository() throws Exception {
        Path repositoriesRoot = tempDir.resolve("git-ssh");
        seedConfigurationRepository(
                repositoriesRoot.resolve("orion-config.git"),
                tempDir.resolve("git-ssh-seed"),
                "/tmp/orion-git-ssh-it",
                "git-ssh-it.xml",
                42080);

        Path privateKey = copyPrivateKey("e2e/trusted-user-rsa.pem", tempDir.resolve("trusted-user-rsa.pem"));
        KeyPair userKey = KeyUtils.readRSAKeyPair(privateKey)
                .valueOrFailure("Trusted user SSH key should load");
        KeyPair hostKey = KeyUtils.readRSAKeyPair(copyPrivateKey(
                        "e2e/server-rsa.pem",
                        tempDir.resolve("server-rsa.pem")))
                .valueOrFailure("Server SSH key should load");

        try (GitSshTestServer gitServer = GitSshTestServer.start(
                repositoriesRoot,
                "git",
                hostKey,
                userKey.getPublic())) {
            Path knownHosts = tempDir.resolve("known_hosts");
            Files.writeString(knownHosts, gitServer.knownHostsLine() + "\n");

            OrionConfiguration configuration = new LocationConfigurationProvider(
                    "git+" + gitServer.repositoryUrl("orion-config.git")
                            + "?branch=" + value(BRANCH)
                            + "&path=" + value(CONFIG_FILE)
                            + "&privateKey=" + value(privateKey.toUri().toString())
                            + "&knownHosts=" + value(knownHosts.toUri().toString()))
                    .readConfiguration();

            assertConfiguration(configuration, "/tmp/orion-git-ssh-it", "git-ssh-it.xml", 42080);
        }
    }

    @Test
    void readsConfigurationFromS3MinioBucket() throws Exception {
        String bucketName = "orion-config-" + UUID.randomUUID().toString().replace("-", "");
        Path secretFile = tempDir.resolve("s3-secret.txt");

        try (MinioS3TestServer s3 = MinioS3TestServer.start(bucketName)) {
            Files.writeString(secretFile, s3.secretAccessKey());
            s3.putObject(CONFIG_FILE, yamlConfiguration(
                    "/tmp/orion-s3-it",
                    "s3-it.xml",
                    43080));

            OrionConfiguration configuration = new LocationConfigurationProvider(
                    "s3://" + s3.bucketName() + "/" + CONFIG_FILE
                            + "?endpoint=" + value(s3.endpoint())
                            + "&region=us-east-1"
                            + "&accessKeyId=" + value(s3.accessKeyId())
                            + "&secretAccessKey=" + value(secretFile.toUri().toString()))
                    .readConfiguration();

            assertConfiguration(configuration, "/tmp/orion-s3-it", "s3-it.xml", 43080);
        }
    }

    private static void seedConfigurationRepository(
            Path bareRepository,
            Path worktree,
            String baseDir,
            String accessControlPath,
            int httpPort) throws Exception {
        GitRepositoryFixture.seedBareRepository(
                bareRepository,
                worktree,
                BRANCH,
                Map.of(CONFIG_FILE, yamlConfiguration(baseDir, accessControlPath, httpPort)));
    }

    private static byte[] yamlConfiguration(String baseDir, String accessControlPath, int httpPort) {
        return """
                bootstrap:
                  baseDir: %s
                  accessControl:
                    paths:
                      - %s
                transport:
                  http:
                    port: %d
                """.formatted(baseDir, accessControlPath, httpPort).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertConfiguration(
            OrionConfiguration configuration,
            String baseDir,
            String accessControlPath,
            int httpPort) {
        assertThat(configuration.getBootstrap().getBaseDir()).isEqualTo(baseDir);
        assertThat(configuration.getBootstrap().getAccessControl().primaryPath()).isEqualTo(accessControlPath);
        assertThat(configuration.getTransport().getHttp().getPort()).isEqualTo(httpPort);
    }

    private static Path copyPrivateKey(String resourceName, Path target) throws Exception {
        try (InputStream input = LocationConfigurationProviderIT.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + resourceName);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.setPosixFilePermissions(target, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        }
        return target;
    }

    private static String value(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
