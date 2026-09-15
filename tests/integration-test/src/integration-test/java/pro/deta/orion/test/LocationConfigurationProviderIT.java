package pro.deta.orion.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.LocationConfigurationProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.test.integration.s3.MinioS3TestServer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocationConfigurationProviderIT {
    private static final String CONFIG_FILE = "config/orion.yml";

    @TempDir
    Path tempDir;

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

    private static String value(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
