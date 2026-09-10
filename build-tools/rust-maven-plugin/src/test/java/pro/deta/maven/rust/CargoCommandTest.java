package pro.deta.maven.rust;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

final class CargoCommandTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void createsLockedOfflineReleaseBuildForTargetWithFeatures() {
        Path manifest = temporaryDirectory.resolve("crate/Cargo.toml");
        Path cargoTarget = temporaryDirectory.resolve("cargo-target");

        CargoCommand command = CargoCommand.create(
                "cargo",
                manifest,
                cargoTarget,
                "build",
                "aarch64-unknown-linux-gnu",
                null,
                true,
                List.of("pty", "serde"),
                true,
                true,
                true);

        assertThat(command.executable()).isEqualTo("cargo");
        assertThat(command.workingDirectory()).isEqualTo(manifest.getParent());
        assertThat(command.arguments()).containsExactly(
                "build",
                "--manifest-path", manifest.toString(),
                "--target", "aarch64-unknown-linux-gnu",
                "--release",
                "--features", "pty,serde",
                "--locked",
                "--offline");
        assertThat(command.environment())
                .containsEntry("CARGO_TARGET_DIR", cargoTarget.toString())
                .containsEntry("CARGO_INCREMENTAL", "1");
    }

    @Test
    void createsProfileTestWithIncrementalityDisabledAndNoOptionalFlags() {
        Path manifest = temporaryDirectory.resolve("Cargo.toml");
        Path cargoTarget = temporaryDirectory.resolve("cargo-target");

        CargoCommand command = CargoCommand.create(
                "/opt/cargo",
                manifest,
                cargoTarget,
                "test",
                null,
                "ci",
                false,
                List.of(),
                false,
                false,
                false);

        assertThat(command.arguments()).containsExactly(
                "test",
                "--manifest-path", manifest.toString(),
                "--profile", "ci");
        assertThat(command.environment()).containsEntry("CARGO_INCREMENTAL", "0");
    }

    @Test
    void rejectsReleaseAndNamedProfileTogether() {
        Path manifest = temporaryDirectory.resolve("Cargo.toml");

        assertThatIllegalArgumentException().isThrownBy(() -> CargoCommand.create(
                "cargo",
                manifest,
                temporaryDirectory.resolve("target"),
                "build",
                null,
                "ci",
                true,
                List.of(),
                true,
                false,
                true))
                .withMessage("release and profile cannot be configured together");
    }
}
