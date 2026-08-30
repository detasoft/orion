package pro.deta.orion.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.schema.config.OrionConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    void readsExplicitS3Location() {
        RecordingS3Client client = new RecordingS3Client(yamlConfiguration(
                "/tmp/orion-s3",
                "s3.xml",
                33080));
        LocationConfigurationProvider provider = providerFor(
                "s3://orion-config/env/orion.yml?endpoint=http://localhost:19000&region=us-east-1"
                        + "&accessKeyId=orion&secretAccessKey=file:/tmp/orion-secret",
                new S3ConfigurationLocationReader(client));

        OrionConfiguration configuration = provider.readConfiguration();

        assertConfiguration(configuration, "/tmp/orion-s3", "s3.xml", 33080);
        assertEquals("orion-config", client.bucket);
        assertEquals("env/orion.yml", client.key);
        assertEquals("http://localhost:19000", client.auth.get("endpoint"));
        assertEquals("us-east-1", client.auth.get("region"));
        assertEquals("orion", client.auth.get("accessKeyId"));
        assertEquals("file:/tmp/orion-secret", client.auth.get("secretAccessKey"));
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

    private static final class RecordingS3Client implements S3ObjectClient {
        private final byte[] content;
        private String bucket;
        private String key;
        private Map<String, String> auth;

        private RecordingS3Client(byte[] content) {
            this.content = content;
        }

        @Override
        public Optional<byte[]> readObject(S3ConfigurationObject object) {
            this.bucket = object.bucket();
            this.key = object.key();
            this.auth = object.auth();
            return Optional.of(content);
        }
    }
}
