package pro.deta.orion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContextTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesPlainStorageLocationRelativeToBaseDir() {
        OrionConfiguration configuration = configuration(tempDir);
        configuration.getStorage().setLocation("repos");

        Path storagePath = new ConfigurationContext(configuration).getFileGitStoragePath();

        assertThat(storagePath).isEqualTo(tempDir.resolve("repos").toAbsolutePath());
    }

    @Test
    void resolvesFileStorageLocation() {
        OrionConfiguration configuration = configuration(tempDir);
        Path storageLocation = tempDir.resolve("storage").resolve("repos");
        configuration.getStorage().setLocation(storageLocation.toUri().toString());

        Path storagePath = new ConfigurationContext(configuration).getFileGitStoragePath();

        assertThat(storagePath).isEqualTo(storageLocation.toAbsolutePath().normalize());
    }

    @Test
    void resolvesRelativeFileStorageLocationFromWorkingDirectory() {
        OrionConfiguration configuration = configuration(tempDir);
        configuration.getStorage().setLocation("file:target/orion-test-repos");

        Path storagePath = new ConfigurationContext(configuration).getFileGitStoragePath();

        assertThat(storagePath).isEqualTo(Paths.get("")
                .resolve("target/orion-test-repos")
                .toAbsolutePath()
                .normalize());
    }

    @Test
    void rejectsNonFileStorageLocationSchemeForFileGitStoragePath() {
        OrionConfiguration configuration = configuration(tempDir);
        configuration.getStorage().setLocation("s3://orion/repositories");

        assertThatThrownBy(() -> new ConfigurationContext(configuration).getFileGitStoragePath())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported repository storage location");
    }

    @Test
    void resolvesEnvBaseDir() {
        OrionConfiguration configuration = configuration(Path.of("unused"));
        configuration.getBootstrap().setBaseDir("env:ORION_ROOT");

        Path baseDir = new ConfigurationContext(configuration, Map.of("ORION_ROOT", tempDir.toString()))
                .getBaseDir();

        assertThat(baseDir).isEqualTo(tempDir.toAbsolutePath());
    }

    @Test
    void resolvesStorageLocationRelativeToEnvBaseDir() {
        OrionConfiguration configuration = configuration(Path.of("unused"));
        configuration.getBootstrap().setBaseDir("env:ORION_ROOT");
        configuration.getStorage().setLocation("repos");

        Path storagePath = new ConfigurationContext(configuration, Map.of("ORION_ROOT", tempDir.toString()))
                .getFileGitStoragePath();

        assertThat(storagePath).isEqualTo(tempDir.resolve("repos").toAbsolutePath());
    }

    @Test
    void rejectsMissingEnvBaseDir() {
        OrionConfiguration configuration = configuration(Path.of("unused"));
        configuration.getBootstrap().setBaseDir("env:ORION_ROOT");

        assertThatThrownBy(() -> new ConfigurationContext(configuration, Map.of()).getBaseDir())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Environment variable ORION_ROOT is not set");
    }

    private OrionConfiguration configuration(Path baseDir) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(baseDir.toString());
        return configuration;
    }
}
