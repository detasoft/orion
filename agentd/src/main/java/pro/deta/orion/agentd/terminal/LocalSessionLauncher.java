package pro.deta.orion.agentd.terminal;

import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agentd.runtime.NativeRuntime;
import pro.deta.orion.agentd.runtime.SessionLaunchResult;
import pro.deta.orion.agentd.runtime.SessionRuntime;
import pro.deta.orion.agentd.runtime.SessionSpec;
import pro.deta.orion.agentd.runtime.WorkspaceReference;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class LocalSessionLauncher {
    private static final String USAGE = """
            Usage: agentd terminal start --session-host PATH --state-dir PATH
                   [--session-id ID] [--cwd PATH] -- COMMAND...
            """;
    private static final int MAX_DIAGNOSTIC_BYTES = 512;

    private LocalSessionLauncher() {
    }

    public static int run(String[] arguments, PrintStream output, PrintStream errors) {
        return run(arguments, output, errors, System.getenv(),
                (executable, sessionsDirectory) -> new NativeRuntime(
                        executable,
                        sessionsDirectory,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2)));
    }

    static int run(
            String[] arguments,
            PrintStream output,
            PrintStream errors,
            Map<String, String> environment,
            RuntimeFactory runtimes
    ) {
        LaunchRequest request;
        try {
            request = parse(arguments, environment);
        } catch (IllegalArgumentException error) {
            errors.println(bounded(error.getMessage()));
            errors.print(USAGE);
            return 2;
        }

        SessionRuntime runtime = runtimes.create(
                request.sessionHost(), request.stateDirectory().resolve("sessions"));
        SessionLaunchResult result = runtime.launch(request.spec());
        if (result instanceof SessionLaunchResult.Started started) {
            output.println("session=" + started.sessionId().value() + " directory=" + started.directory());
            return 0;
        }
        SessionLaunchResult.Failed failed = (SessionLaunchResult.Failed) result;
        errors.println(failed.kind() + ": " + bounded(failed.detail()));
        return 1;
    }

    private static LaunchRequest parse(String[] arguments, Map<String, String> environment) {
        Path executable = null;
        Path stateDirectory = null;
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        String sessionId = null;
        Set<String> present = new HashSet<>();
        int index = 0;
        while (index < arguments.length && !"--".equals(arguments[index])) {
            String option = arguments[index++];
            if (!present.add(option)) {
                throw new IllegalArgumentException("Duplicate terminal start option: " + option);
            }
            String value = value(arguments, index++, option);
            switch (option) {
                case "--session-host" -> executable = path(value, option);
                case "--state-dir" -> stateDirectory = path(value, option);
                case "--session-id" -> sessionId = value;
                case "--cwd" -> workingDirectory = path(value, option);
                default -> throw new IllegalArgumentException("Unknown terminal start option: " + option);
            }
        }
        if (index >= arguments.length || !"--".equals(arguments[index])) {
            throw new IllegalArgumentException("Missing -- before the child command");
        }
        List<String> command = List.copyOf(Arrays.asList(arguments).subList(index + 1, arguments.length));
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Missing child command");
        }
        if (executable == null) {
            throw new IllegalArgumentException("Missing required option: --session-host");
        }
        if (stateDirectory == null) {
            throw new IllegalArgumentException("Missing required option: --state-dir");
        }

        SessionId resolvedSessionId = new SessionId(
                sessionId == null ? "local-session-" + UUID.randomUUID() : sessionId);
        CommandId startCommandId = new CommandId("local-start-" + UUID.randomUUID());
        String terminalType = safeEnvironment(environment.get("TERM"))
                ? environment.get("TERM") : "xterm-256color";
        Optional<String> colorTerminal = safeEnvironment(environment.get("COLORTERM"))
                ? Optional.of(environment.get("COLORTERM")) : Optional.empty();
        SessionSpec spec = new SessionSpec(
                resolvedSessionId,
                startCommandId,
                command,
                new WorkspaceReference.ExistingDirectory(workingDirectory),
                Map.of(),
                80,
                24,
                terminalType,
                colorTerminal,
                SessionSpec.Sandbox.none());
        return new LaunchRequest(executable, stateDirectory, spec);
    }

    private static String value(String[] arguments, int index, String option) {
        if (index >= arguments.length || "--".equals(arguments[index])) {
            throw new IllegalArgumentException("Missing value for terminal start option: " + option);
        }
        return arguments[index];
    }

    private static Path path(String value, String option) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("Invalid path for terminal start option: " + option, error);
        }
    }

    private static boolean safeEnvironment(String value) {
        return value != null
                && !value.isEmpty()
                && value.indexOf('=') < 0
                && value.indexOf('\0') < 0
                && value.getBytes(StandardCharsets.UTF_8).length <= 128;
    }

    private static String bounded(String detail) {
        String value = detail == null ? "unknown failure" : detail;
        if (value.getBytes(StandardCharsets.UTF_8).length <= MAX_DIAGNOSTIC_BYTES) {
            return value;
        }
        int end = value.length();
        while (end > 0
                && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > MAX_DIAGNOSTIC_BYTES) {
            end--;
        }
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record LaunchRequest(Path sessionHost, Path stateDirectory, SessionSpec spec) {
    }

    @FunctionalInterface
    interface RuntimeFactory {
        SessionRuntime create(Path executable, Path sessionsDirectory);
    }
}
