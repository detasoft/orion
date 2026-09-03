package pro.deta.orion.git.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class GitCommandRunner {
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final List<String> CONFIGURATION = List.of(
            "-c", "init.defaultBranch=" + GitScenarioContext.DEFAULT_BRANCH,
            "-c", "user.name=" + GitScenarioContext.IDENTITY_NAME,
            "-c", "user.email=" + GitScenarioContext.IDENTITY_EMAIL,
            "-c", "commit.gpgSign=false",
            "-c", "core.autocrlf=false",
            "-c", "core.fileMode=false");
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_CONFIG_GLOBAL", nullConfigurationPath(),
            "GIT_TERMINAL_PROMPT", "0",
            "LC_ALL", "C",
            "TZ", "UTC");

    private final String executable;
    private final Duration timeout;

    GitCommandRunner(String executable, Duration timeout) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    String executable() {
        return executable;
    }

    Result run(Path directory, Map<String, String> environment, String... arguments) throws IOException {
        return runResult(directory, environment, arguments).requireSuccess();
    }

    Result runResult(Path directory, Map<String, String> environment, String... arguments) throws IOException {
        List<String> command = command(arguments);
        Path outputFile = Files.createTempFile("orion-git-command-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            builder.environment().putAll(ENVIRONMENT);
            builder.environment().putAll(environment);

            Process process;
            try {
                process = builder.start();
            } catch (IOException error) {
                throw new IOException("Unable to start canonical Git command " + format(command)
                        + ": " + error.getMessage(), error);
            }
            if (!waitFor(process, timeout)) {
                terminate(process);
                throw new IOException("Timed out after " + timeout + " running canonical Git command "
                        + format(command) + output(outputFile));
            }

            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            return new Result(process.exitValue(), output, format(command));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    Result run(Path directory, String... arguments) throws IOException {
        return run(directory, Map.of(), arguments);
    }

    Result runResult(Path directory, String... arguments) throws IOException {
        return runResult(directory, Map.of(), arguments);
    }

    String version() throws IOException {
        return run(null, "--version").trimmed();
    }

    Map<String, String> baseEnvironment() {
        return ENVIRONMENT;
    }

    List<String> command(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(CONFIGURATION);
        command.addAll(List.of(arguments));
        return command;
    }

    private static boolean waitFor(Process process, Duration duration) throws IOException {
        try {
            return process.waitFor(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for canonical Git", error);
        }
    }

    private static void terminate(Process process) throws IOException {
        process.destroy();
        if (!waitFor(process, TERMINATION_GRACE)) {
            process.destroyForcibly();
            if (!waitFor(process, TERMINATION_GRACE)) {
                throw new IOException("Canonical Git process did not terminate after forced shutdown");
            }
        }
    }

    private static String output(Path outputFile) throws IOException {
        return appendOutput(Files.readString(outputFile, StandardCharsets.UTF_8));
    }

    private static String appendOutput(String output) {
        return output.isBlank() ? "" : System.lineSeparator() + output.stripTrailing();
    }

    private static String format(List<String> command) {
        return String.join(" ", command);
    }

    private static String nullConfigurationPath() {
        return System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null";
    }

    record Result(int exitCode, String output, String command) {
        boolean successful() {
            return exitCode == 0;
        }

        Result requireSuccess() throws IOException {
            if (!successful()) {
                throw new IOException("Canonical Git command failed with exit " + exitCode + ": "
                        + command + appendOutput(output));
            }
            return this;
        }

        String trimmed() {
            return output.strip();
        }
    }
}
