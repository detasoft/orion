package pro.deta.maven.rust;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Builds a Cargo binary and copies it to a target-specific Maven output directory.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public final class CargoBuildMojo extends AbstractCargoMojo {
    @Parameter(property = "rust.binary", required = true)
    String binary;

    @Parameter(property = "rust.outputDirectory", defaultValue = "${project.build.directory}/native",
            required = true)
    File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if (isSkipped()) {
            return;
        }
        runCargo("build");
        String resolvedTarget = target == null || target.isBlank() ? detectHostTarget() : target;
        copyBinary(resolvedTarget);
    }

    private String detectHostTarget() throws MojoExecutionException {
        CargoCommand version = new CargoCommand(
                cargoExecutable,
                manifest.toPath().toAbsolutePath().getParent(),
                List.of("-vV"),
                Map.of());
        String output = new CargoRunner().output(version);
        for (String line : output.lines().toList()) {
            if (line.startsWith("host: ") && !line.substring("host: ".length()).isBlank()) {
                return line.substring("host: ".length()).strip();
            }
        }
        throw new MojoExecutionException("Cargo -vV output does not contain a host target");
    }

    private void copyBinary(String resolvedTarget) throws MojoExecutionException {
        String profileDirectory = release
                ? "release"
                : profile == null || profile.isBlank() ? "debug" : profile;
        Path sourceDirectory = cargoTargetDirectory.toPath();
        if (target != null && !target.isBlank()) {
            sourceDirectory = sourceDirectory.resolve(target);
        }
        Path source = sourceDirectory.resolve(profileDirectory).resolve(binary);
        Path destination = outputDirectory.toPath().resolve(resolvedTarget).resolve(binary);
        try {
            Files.createDirectories(destination.getParent());
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            if (!destination.toFile().setExecutable(true, false)) {
                throw new IOException("cannot make copied binary executable");
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Cannot copy Cargo binary from " + source + " to " + destination,
                    e);
        }
    }
}
