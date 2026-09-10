package pro.deta.maven.rust;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

final class CargoRunnerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void passesArgumentsEnvironmentAndWorkingDirectoryToProcess() throws Exception {
        Path capture = temporaryDirectory.resolve("capture.txt");
        Path executable = executable("""
                #!/bin/sh
                printf '%s\n' "$PWD" "$CARGO_INCREMENTAL" "$@" > "$CAPTURE_FILE"
                """);
        CargoCommand command = new CargoCommand(
                executable.toString(),
                temporaryDirectory,
                List.of("test", "--locked"),
                Map.of("CARGO_INCREMENTAL", "0", "CAPTURE_FILE", capture.toString()));

        new CargoRunner().run(command);

        List<String> captured = Files.readAllLines(capture);
        assertThat(Path.of(captured.get(0))).isEqualTo(temporaryDirectory.toRealPath());
        assertThat(captured.subList(1, captured.size())).containsExactly("0", "test", "--locked");
    }

    @Test
    void reportsNonZeroCargoExitCode() throws Exception {
        Path executable = executable("""
                #!/bin/sh
                exit 7
                """);
        CargoCommand command = new CargoCommand(
                executable.toString(), temporaryDirectory, List.of("test"), Map.of());

        assertThatExceptionOfType(MojoExecutionException.class)
                .isThrownBy(() -> new CargoRunner().run(command))
                .withMessageContaining("exited with code 7");
    }

    private Path executable(String content) throws IOException {
        Path executable = Files.writeString(temporaryDirectory.resolve("fake-cargo"), content);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }
}
