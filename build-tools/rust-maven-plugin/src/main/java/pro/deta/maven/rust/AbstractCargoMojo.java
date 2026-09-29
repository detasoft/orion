package pro.deta.maven.rust;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.List;

abstract class AbstractCargoMojo extends AbstractMojo {
    @Parameter(property = "rust.cargo", defaultValue = "cargo", required = true)
    String cargoExecutable;

    @Parameter(property = "rust.manifest", required = true)
    File manifest;

    @Parameter(property = "rust.cargoTargetDirectory", defaultValue = "${project.build.directory}/cargo",
            required = true)
    File cargoTargetDirectory;

    @Parameter(property = "rust.target")
    String target;

    @Parameter(property = "rust.profile")
    String profile;

    @Parameter(property = "rust.release", defaultValue = "false")
    boolean release;

    @Parameter(property = "rust.features")
    List<String> features = List.of();

    @Parameter(property = "rust.locked", defaultValue = "true")
    boolean locked;

    @Parameter(property = "rust.offline", defaultValue = "false")
    boolean offline;

    @Parameter(property = "rust.incremental", defaultValue = "true")
    boolean incremental;

    @Parameter(property = "rust.skip", defaultValue = "false")
    boolean skip;

    final void runCargo(String goal, String resolvedTarget) throws MojoExecutionException {
        CargoCommand command = cargoCommand(goal, resolvedTarget);
        getLog().info("Running " + String.join(" ", command.processArguments()));
        new CargoRunner().run(command);
    }

    final void validateConfiguration() throws MojoExecutionException {
        if (manifest == null || !manifest.isFile()) {
            throw new MojoExecutionException("Cargo manifest does not exist: " + manifest);
        }
        if (cargoTargetDirectory == null) {
            throw new MojoExecutionException("cargoTargetDirectory is required");
        }
    }

    final CargoCommand cargoCommand(String goal, String resolvedTarget) throws MojoExecutionException {
        validateConfiguration();
        try {
            return CargoCommand.create(
                    cargoExecutable,
                    manifest.toPath(),
                    cargoTargetDirectory.toPath(),
                    goal,
                    resolvedTarget,
                    profile,
                    release,
                    features,
                    locked,
                    offline,
                    incremental);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    final boolean isSkipped() {
        if (skip) {
            getLog().info("Skipping Cargo execution");
        }
        return skip;
    }
}
