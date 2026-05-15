package pro.deta.orion.bootstrap;

import java.io.PrintStream;
import java.util.Arrays;

public final class OrionCli {
    private final ServerRunner serverRunner;
    private final Verifier verifier;

    public OrionCli(ServerRunner serverRunner, Verifier verifier) {
        this.serverRunner = serverRunner;
        this.verifier = verifier;
    }

    public int run(String[] arguments, PrintStream output, PrintStream errors) {
        try {
            if (arguments.length == 0) {
                return serverRunner.run();
            }

            String command = arguments[0];
            return switch (command) {
                case "run" -> runServerCommand(arguments, errors);
                case "verify" -> verifier.verify(Arrays.copyOfRange(arguments, 1, arguments.length), output, errors);
                case "help", "--help", "-h" -> {
                    printUsage(output);
                    yield 0;
                }
                default -> {
                    errors.println("Unknown command: " + command);
                    printUsage(errors);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException e) {
            errors.println(e.getMessage());
            return 2;
        } catch (Exception e) {
            errors.println("Command failed: " + e.getMessage());
            return 1;
        }
    }

    private int runServerCommand(String[] arguments, PrintStream errors) throws Exception {
        if (arguments.length > 1) {
            errors.println("The run command does not accept arguments yet.");
            printUsage(errors);
            return 2;
        }

        return serverRunner.run();
    }

    private void printUsage(PrintStream stream) {
        stream.println("Usage: orion [run|verify|help]");
        stream.println("  run     start Orion in the current process");
        stream.println("  verify  verify this release artifact with a detached GPG signature");
    }

    @FunctionalInterface
    public interface ServerRunner {
        int run() throws Exception;
    }

    @FunctionalInterface
    public interface Verifier {
        int verify(String[] arguments, PrintStream output, PrintStream errors) throws Exception;
    }
}
