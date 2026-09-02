package pro.deta.orion.agentd;

import pro.deta.orion.agentd.core.Agent;
import pro.deta.orion.agentd.core.AgentConfiguration;

import java.io.PrintStream;

public final class AgentdMain {
    private static final String USAGE = """
            Usage: java -jar agentd.jar --server HTTPS_URI [options]

            Options:
              --state-dir PATH       persistent AgentD state directory
              --max-frame-bytes N    maximum Agent protocol frame size
              --agent-version VALUE  version reported during registration
              --help                 show this help
            """;

    private AgentdMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream output, PrintStream errors) {
        if (args.length == 1 && "--help".equals(args[0])) {
            output.print(USAGE);
            return 0;
        }

        AgentConfiguration configuration;
        try {
            configuration = AgentConfiguration.parse(args);
        } catch (IllegalArgumentException e) {
            errors.println(e.getMessage());
            errors.print(USAGE);
            return 2;
        }

        try (Agent agent = Agent.create(configuration)) {
            Thread shutdownHook = new Thread(agent::close, "agentd-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                agent.start();
                agent.awaitTermination();
                return 0;
            } finally {
                removeShutdownHook(shutdownHook);
            }
        } catch (RuntimeException e) {
            errors.println("AgentD failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.println("AgentD interrupted");
            return 1;
        }
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }
}
