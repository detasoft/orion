package pro.deta.maven.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class CargoBuildMojoTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void copiesBuiltBinaryUnderDetectedHostTarget() throws Exception {
        Path cargoTarget = temporaryDirectory.resolve("cargo-target");
        Path manifest = Files.writeString(temporaryDirectory.resolve("Cargo.toml"), "[package]\n");
        Path cargo = Files.writeString(temporaryDirectory.resolve("fake-cargo"), """
                #!/bin/sh
                if [ "$1" = "-vV" ]; then
                    printf 'cargo 1.97.0\nhost: test-host\n'
                    exit 0
                fi
                mkdir -p "$CARGO_TARGET_DIR/debug"
                printf 'native-binary' > "$CARGO_TARGET_DIR/debug/session-host"
                """);
        assertThat(cargo.toFile().setExecutable(true)).isTrue();
        CargoBuildMojo mojo = new CargoBuildMojo();
        mojo.cargoExecutable = cargo.toString();
        mojo.manifest = manifest.toFile();
        mojo.cargoTargetDirectory = cargoTarget.toFile();
        mojo.outputDirectory = temporaryDirectory.resolve("output").toFile();
        mojo.binary = "session-host";
        mojo.features = List.of();
        mojo.locked = true;
        mojo.incremental = true;

        mojo.execute();

        Path output = mojo.outputDirectory.toPath().resolve("test-host/session-host");
        assertThat(output).hasContent("native-binary");
        assertThat(output).isExecutable();
    }
}
