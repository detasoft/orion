package pro.deta.orion.agentd.terminal;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;

public final class LocalTerminalCommand {
    private static final String USAGE = """
            Usage: agentd terminal start [--session-host PATH] --state-dir PATH
                   [--session-id ID] [--cwd PATH] [--ack-journal] -- COMMAND...
                   agentd terminal attach --session-dir PATH [--ack-journal]
            --ack-journal permits retention to delete history needed by later attaches; no server durability.
            """;

    private final Attacher attacher;

    LocalTerminalCommand(Attacher attacher) {
        this.attacher = java.util.Objects.requireNonNull(attacher, "attacher");
    }

    public static int run(String[] arguments, PrintStream output, PrintStream errors) {
        LocalTerminalAttacher attacher = LocalTerminalAttacher.create();
        return new LocalTerminalCommand(attacher::attach).execute(arguments, output, errors);
    }

    int execute(
            String[] arguments,
            PrintStream output,
            PrintStream errors
    ) {
        if (arguments.length == 0) {
            errors.print(USAGE);
            return 2;
        }
        if ("start".equals(arguments[0])) {
            return LocalSessionLauncher.run(
                    Arrays.copyOfRange(arguments, 1, arguments.length), output, errors, attacher);
        }
        if (!"attach".equals(arguments[0])) {
            errors.println("Unknown terminal command: " + arguments[0]);
            errors.print(USAGE);
            return 2;
        }
        String directory = null;
        boolean acknowledgeJournal = false;
        for (int index = 1; index < arguments.length; index++) {
            if ("--ack-journal".equals(arguments[index]) && !acknowledgeJournal) {
                acknowledgeJournal = true;
            } else if ("--session-dir".equals(arguments[index]) && directory == null
                    && index + 1 < arguments.length && !arguments[index + 1].startsWith("--")) {
                directory = arguments[++index];
            } else {
                errors.println("Invalid or duplicate terminal attach option");
                errors.print(USAGE);
                return 2;
            }
        }
        if (directory == null) {
            errors.println("terminal attach requires --session-dir PATH");
            errors.print(USAGE);
            return 2;
        }
        Path sessionDirectory;
        try {
            sessionDirectory = Path.of(directory).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            errors.println("Invalid terminal attach path: --session-dir");
            errors.print(USAGE);
            return 2;
        }
        return attacher.attach(sessionDirectory, acknowledgeJournal, errors);
    }

    @FunctionalInterface
    interface Attacher {
        int attach(Path sessionDirectory, boolean acknowledgeJournal, PrintStream errors);
    }
}
