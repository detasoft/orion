package pro.deta.maven.rust;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class CargoRunner {
    void run(CargoCommand command) throws MojoExecutionException {
        ProcessBuilder processBuilder = processBuilder(command).inheritIO();
        int exitCode = waitFor(start(processBuilder, command));
        if (exitCode != 0) {
            throw new MojoExecutionException("Cargo exited with code " + exitCode);
        }
    }

    String output(CargoCommand command) throws MojoExecutionException {
        Process process = start(processBuilder(command).redirectErrorStream(true), command);
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = waitFor(process);
            if (exitCode != 0) {
                throw new MojoExecutionException("Cargo exited with code " + exitCode + ": " + output.strip());
            }
            return output;
        } catch (IOException e) {
            process.destroyForcibly();
            throw new MojoExecutionException("Cannot read Cargo output", e);
        }
    }

    private static ProcessBuilder processBuilder(CargoCommand command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command.processArguments());
        processBuilder.directory(command.workingDirectory().toFile());
        processBuilder.environment().putAll(command.environment());
        return processBuilder;
    }

    private static Process start(
            ProcessBuilder processBuilder,
            CargoCommand command) throws MojoExecutionException {
        try {
            return processBuilder.start();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot start Cargo executable " + command.executable(), e);
        }
    }

    private static int waitFor(Process process) throws MojoExecutionException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new MojoExecutionException("Interrupted while waiting for Cargo", e);
        }
    }
}
