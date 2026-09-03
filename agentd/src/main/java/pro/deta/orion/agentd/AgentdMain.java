package pro.deta.orion.agentd;

import pro.deta.orion.agentd.core.Agent;
import pro.deta.orion.agentd.core.AgentConfiguration;
import pro.deta.orion.agentd.core.AgentLaunchContext;
import pro.deta.orion.agentd.core.LaunchPermit;
import pro.deta.orion.agentd.core.LaunchPermitReader;

import java.io.InputStream;
import java.io.PrintStream;

public final class AgentdMain {
    private static final String USAGE = """
            Usage: java -jar agentd.jar --server HTTPS_URI [options]

            Options:
              --state-dir PATH       persistent AgentD state directory
              --agent-id ID          server-assigned stable agent identity
              --generation N         server-assigned positive launch generation
              --launch-id UUID       server-assigned launch identity
              --max-frame-bytes N    maximum Agent protocol frame size
              --agent-version VALUE  version reported during registration
              --session-host PATH    bundled native session-host executable
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
        return run(args, System.in, output, errors, AgentdMain::launch);
    }

    static int run(
            String[] args,
            InputStream input,
            PrintStream output,
            PrintStream errors,
            Launcher launcher
    ) {
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

        try (LaunchPermit permit = new LaunchPermitReader().read(input);
             AgentLaunchContext context = AgentLaunchContext.create(configuration, permit)) {
            launcher.launch(configuration, context);
            return 0;
        } catch (java.io.IOException e) {
            errors.println("Invalid AgentD launch permit");
            return 2;
        } catch (RuntimeException e) {
            errors.println("AgentD failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.println("AgentD interrupted");
            return 1;
        }
    }

    private static void launch(AgentConfiguration configuration, AgentLaunchContext context)
            throws InterruptedException {
        try (Agent agent = Agent.create(configuration, context)) {
            Thread shutdownHook = new Thread(agent::close, "agentd-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                agent.start();
                agent.awaitTermination();
            } finally {
                removeShutdownHook(shutdownHook);
            }
        }
    }

    @FunctionalInterface
    interface Launcher {
        void launch(AgentConfiguration configuration, AgentLaunchContext context) throws InterruptedException;
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }
}
