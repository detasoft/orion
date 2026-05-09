package pro.deta.orion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContextTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesPlainStorageLocationRelativeToBaseDir() {
        OrionConfiguration configuration = configuration(tempDir);
        configuration.getStorage().setLocation("repos");

        Path storagePath = new ConfigurationContext(configuration).getGitStoragePath();

        assertThat(storagePath).isEqualTo(tempDir.resolve("repos").toAbsolutePath());
    }

    @Test
    void resolvesFileStorageLocation() {
        OrionConfiguration configuration = configuration(tempDir);
        Path storageLocation = tempDir.resolve("storage").resolve("repos");
        configuration.getStorage().setLocation(storageLocation.toUri().toString());

        Path storagePath = new ConfigurationContext(configuration).getGitStoragePath();

        assertThat(storagePath).isEqualTo(storageLocation.toAbsolutePath().normalize());
    }

    @Test
    void resolvesRelativeFileStorageLocationFromWorkingDirectory() {
        OrionConfiguration configuration = configuration(tempDir);
        configuration.getStorage().setLocation("file:target/orion-test-repos");

        Path storagePath = new ConfigurationContext(configuration).getGitStoragePath();

        assertThat(storagePath).isEqualTo(Paths.get("")
                .resolve("target/orion-test-repos")
                .toAbsolutePath()
                .normalize());
    }

    @Test
    void rejectsUnsupportedStorageLocationScheme() {
        OrionConfiguration configuration = configuration(tempDir);
        configuration.getStorage().setLocation("s3://orion/repositories");

        assertThatThrownBy(() -> new ConfigurationContext(configuration).getGitStoragePath())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported repository storage location");
    }

    private OrionConfiguration configuration(Path baseDir) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(baseDir.toString());
        return configuration;
    }
}
