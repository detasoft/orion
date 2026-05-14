package pro.deta.orion.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocationConfigurationProviderTest {
    @TempDir
    private Path tempDir;

    @Test
    void readsFirstAvailableDefaultLocation() {
        LocationConfigurationProvider provider = new LocationConfigurationProvider(
                new String[]{"classpath://location-provider-default.yml"},
                false);

        OrionConfiguration configuration = provider.readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-default-location", "default-location.xml", 17080);
    }

    @Test
    void readsExplicitClasspathYamlLocation() {
        OrionConfiguration configuration = new LocationConfigurationProvider(
                "classpath://file-provider-classpath.yml")
                .readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-classpath-yaml", "classpath-yaml.xml", 18080);
    }

    @Test
    void readsExplicitClasspathTomlLocation() {
        OrionConfiguration configuration = new LocationConfigurationProvider(
                "classpath://file-provider-classpath.toml")
                .readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-classpath-toml", "classpath-toml.xml", 19080);
    }

    @Test
    void readsExplicitFilesystemYamlLocation() throws Exception {
        Path configFile = tempDir.resolve("orion.yml");
        Files.writeString(configFile, """
                bootstrap:
                  baseDir: /tmp/orion-file-yaml
                  accessControl:
                    paths:
                      - file-yaml.xml
                transport:
                  http:
                    port: 28080
                """);

        OrionConfiguration configuration = new LocationConfigurationProvider(configFile.toString())
                .readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-file-yaml", "file-yaml.xml", 28080);
    }

    @Test
    void readsExplicitFileUriYamlLocation() throws Exception {
        Path configFile = tempDir.resolve("orion-file-uri.yml");
        Files.writeString(configFile, """
                bootstrap:
                  baseDir: /tmp/orion-file-uri
                  accessControl:
                    paths:
                      - file-uri.xml
                transport:
                  http:
                    port: 28580
                """);

        OrionConfiguration configuration = new LocationConfigurationProvider(configFile.toUri().toString())
                .readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-file-uri", "file-uri.xml", 28580);
    }

    @Test
    void readsExplicitFilesystemTomlLocation() throws Exception {
        Path configFile = tempDir.resolve("orion.toml");
        Files.writeString(configFile, """
                [bootstrap]
                baseDir = "/tmp/orion-file-toml"

                [bootstrap.accessControl]
                paths = ["file-toml.xml"]

                [transport.http]
                port = 29080
                """);

        OrionConfiguration configuration = new LocationConfigurationProvider(configFile.toString())
                .readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-file-toml", "file-toml.xml", 29080);
    }

    @Test
    void readsExplicitGitSshLocation() {
        RecordingGitClient client = new RecordingGitClient(yamlConfiguration(
                "/tmp/orion-git-ssh",
                "git-ssh.xml",
                30080));
        LocationConfigurationProvider provider = providerFor(
                "git+ssh://git@example.test/team/orion.git?ref=main&path=config/orion.yml",
                new GitConfigurationLocationReader(client));

        OrionConfiguration configuration = provider.readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-git-ssh", "git-ssh.xml", 30080);
        assertEquals("ssh://git@example.test/team/orion.git", client.remoteUri);
        assertEquals("main", client.ref);
        assertEquals("config/orion.yml", client.path);
    }

    @Test
    void readsExplicitGitHttpLocation() {
        RecordingGitClient client = new RecordingGitClient(tomlConfiguration(
                "/tmp/orion-git-http",
                "git-http.xml",
                31080));
        LocationConfigurationProvider provider = providerFor(
                "git+http://example.test/team/orion.git?branch=develop&file=config/orion.toml",
                new GitConfigurationLocationReader(client));

        OrionConfiguration configuration = provider.readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-git-http", "git-http.xml", 31080);
        assertEquals("http://example.test/team/orion.git", client.remoteUri);
        assertEquals("develop", client.ref);
        assertEquals("config/orion.toml", client.path);
    }

    @Test
    void readsExplicitGitHttpsLocation() {
        RecordingGitClient client = new RecordingGitClient(yamlConfiguration(
                "/tmp/orion-git-https",
                "git-https.xml",
                32080));
        LocationConfigurationProvider provider = providerFor(
                "git+https://example.test/team/orion.git?path=config/orion.yml",
                new GitConfigurationLocationReader(client));

        OrionConfiguration configuration = provider.readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-git-https", "git-https.xml", 32080);
        assertEquals("https://example.test/team/orion.git", client.remoteUri);
        assertEquals("HEAD", client.ref);
        assertEquals("config/orion.yml", client.path);
    }

    @Test
    void readsExplicitS3Location() {
        RecordingS3Client client = new RecordingS3Client(yamlConfiguration(
                "/tmp/orion-s3",
                "s3.xml",
                33080));
        LocationConfigurationProvider provider = providerFor(
                "s3://orion-config/env/orion.yml",
                new S3ConfigurationLocationReader(client));

        OrionConfiguration configuration = provider.readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-s3", "s3.xml", 33080);
        assertEquals("orion-config", client.bucket);
        assertEquals("env/orion.yml", client.key);
    }

    private static LocationConfigurationProvider providerFor(String location, ConfigurationLocationReader reader) {
        return new LocationConfigurationProvider(new String[]{location}, true, List.of(reader));
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

    private static byte[] tomlConfiguration(String baseDir, String accessControlPath, int httpPort) {
        return """
                [bootstrap]
                baseDir = "%s"

                [bootstrap.accessControl]
                paths = ["%s"]

                [transport.http]
                port = %d
                """.formatted(baseDir, accessControlPath, httpPort).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertConfiguration(
            OrionConfiguration configuration,
            String baseDir,
            String accessControlPath,
            int httpPort) {
        assertEquals(baseDir, configuration.getBootstrap().getBaseDir());
        assertEquals(accessControlPath, configuration.getBootstrap().getAccessControl().primaryPath());
        assertEquals(httpPort, configuration.getTransport().getHttp().getPort());
    }

    private static final class RecordingGitClient implements GitRepositoryFileClient {
        private final byte[] content;
        private String remoteUri;
        private String ref;
        private String path;

        private RecordingGitClient(byte[] content) {
            this.content = content;
        }

        @Override
        public Optional<byte[]> readFile(String remoteUri, String ref, String path) {
            this.remoteUri = remoteUri;
            this.ref = ref;
            this.path = path;
            return Optional.of(content);
        }
    }

    private static final class RecordingS3Client implements S3ObjectClient {
        private final byte[] content;
        private String bucket;
        private String key;

        private RecordingS3Client(byte[] content) {
            this.content = content;
        }

        @Override
        public Optional<byte[]> readObject(String bucket, String key) {
            this.bucket = bucket;
            this.key = key;
            return Optional.of(content);
        }
    }
}
