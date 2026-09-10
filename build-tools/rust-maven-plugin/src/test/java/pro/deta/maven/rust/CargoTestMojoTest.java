package pro.deta.maven.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class CargoTestMojoTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void recordsSuccessfulTestExecutionInPluginContext() throws Exception {
        Path manifest = Files.writeString(temporaryDirectory.resolve("Cargo.toml"), "[package]\n");
        Path cargo = Files.writeString(temporaryDirectory.resolve("fake-cargo"), """
                #!/bin/sh
                exit 0
                """);
        assertThat(cargo.toFile().setExecutable(true)).isTrue();
        Map<String, Object> pluginContext = new HashMap<>();
        CargoTestMojo mojo = new CargoTestMojo();
        mojo.setPluginContext(pluginContext);
        mojo.cargoExecutable = cargo.toString();
        mojo.manifest = manifest.toFile();
        mojo.cargoTargetDirectory = temporaryDirectory.resolve("cargo-target").toFile();
        mojo.features = List.of();
        mojo.locked = true;
        mojo.incremental = true;

        mojo.execute();

        assertThat(pluginContext).containsEntry(CargoTestMojo.TEST_EXECUTED, true);
    }
}
