package pro.deta.maven.rust;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class CargoBuildMojoTest {
    @TempDir
    private Path temporaryDirectory;

    @ParameterizedTest
    @CsvSource(value = {
            "DEFAULT, false, DEFAULT, dev, debug",
            "dev, false, DEFAULT, dev, debug",
            "test, false, DEFAULT, test, debug",
            "release, false, DEFAULT, release, release",
            "bench, false, DEFAULT, bench, release",
            "custom, false, DEFAULT, custom, custom",
            "DEFAULT, true, DEFAULT, release, release",
            "dev, false, explicit-target, dev, debug"
    }, nullValues = "DEFAULT")
    void copiesBuiltBinaryFromSelectedProfile(String profile, boolean release, String target,
                                             String selectedProfile, String profileDirectory) throws Exception {
        Path cargoTarget = temporaryDirectory.resolve("cargo-target");
        Path manifest = Files.writeString(temporaryDirectory.resolve("Cargo.toml"), "[package]\n");
        Path cargo = Files.writeString(temporaryDirectory.resolve("fake-cargo"), """
                #!/bin/sh
                if [ "$1" = "-vV" ]; then
                    printf 'cargo 1.97.0\nhost: test-host\n'
                    exit 0
                fi
                selected_profile=dev
                selected_target=
                while [ "$#" -gt 0 ]; do
                    case "$1" in
                        --profile) selected_profile="$2"; shift ;;
                        --release) selected_profile=release ;;
                        --target) selected_target="$2"; shift ;;
                    esac
                    shift
                done
                case "$selected_profile" in
                    dev|test) profile_directory=debug ;;
                    release|bench) profile_directory=release ;;
                    *) profile_directory="$selected_profile" ;;
                esac
                output="$CARGO_TARGET_DIR"
                if [ -n "$selected_target" ]; then
                    output="$output/$selected_target"
                fi
                mkdir -p "$output/$profile_directory"
                printf '%s:%s' "$selected_profile" "$selected_target" > "$output/$profile_directory/session-host"
                """);
        assertThat(cargo.toFile().setExecutable(true)).isTrue();
        CargoBuildMojo mojo = new CargoBuildMojo();
        mojo.cargoExecutable = cargo.toString();
        mojo.manifest = manifest.toFile();
        mojo.cargoTargetDirectory = cargoTarget.toFile();
        mojo.outputDirectory = temporaryDirectory.resolve("output").toFile();
        mojo.binary = "session-host";
        mojo.profile = profile;
        mojo.release = release;
        mojo.target = target;
        mojo.features = List.of();
        mojo.locked = true;
        mojo.incremental = true;

        if (profile != null && !profile.equals(profileDirectory)) {
            Path staleDirectory = target == null ? cargoTarget : cargoTarget.resolve(target);
            Path staleBinary = staleDirectory.resolve(profile).resolve("session-host");
            Files.createDirectories(staleBinary.getParent());
            Files.writeString(staleBinary, "stale-binary");
        }

        mojo.execute();

        String resolvedTarget = target == null ? "test-host" : target;
        Path output = mojo.outputDirectory.toPath().resolve(resolvedTarget).resolve("session-host");
        assertThat(output).hasContent(selectedProfile + ":" + (target == null ? "" : target));
        assertThat(output).isExecutable();
    }
}
