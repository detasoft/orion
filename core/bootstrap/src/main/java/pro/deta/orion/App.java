package pro.deta.orion;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.ConfigurationProvider;
import pro.deta.orion.config.LocationConfigurationProvider;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;

import java.io.IOException;
import java.io.PrintStream;

@Slf4j
public class App {
    public static void main(String[] args) throws IOException {
        int exitCode = runCommand(args, System.out, System.err, ReleaseVerifier.systemDefault());
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runCommand(String[] args, PrintStream output, PrintStream errors, ReleaseVerifier verifier)
            throws IOException {
        AppOptions options;
        try {
            options = AppOptions.parse(args);
        } catch (IllegalArgumentException e) {
            errors.println(e.getMessage());
            errors.print(usageFor(args));
            return 2;
        }

        if (options.helpRequested()) {
            output.print(options.command() == AppOptions.Command.VERIFY
                    ? AppOptions.verifyUsage()
                    : AppOptions.usage());
            return 0;
        }

        if (options.command() == AppOptions.Command.VERIFY) {
            return verifier.verify(options, output, errors);
        }

        return runApplication(options);
    }

    private static int runApplication(AppOptions options) {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(configurationProvider(options))
                .build();
        return run(orionComponent.orionApplicationLifecycle(), true);
    }

    private static String usageFor(String[] args) {
        if (args.length > 0 && "verify".equals(args[0])) {
            return AppOptions.verifyUsage();
        }
        return AppOptions.usage();
    }

    static ConfigurationProvider configurationProvider(AppOptions options) {
        if (options.configurationLocation() == null) {
            return new LocationConfigurationProvider();
        }
        return new LocationConfigurationProvider(options.configurationLocation());
    }

    static int run(OrionApplicationLifecycle lifecycle, boolean installShutdownHook) {
        ApplicationState state = lifecycle.runApplication();
        if (state != ApplicationState.UP) {
            log.error("Orion startup failed with state {}", state);
            return 1;
        }

        Thread shutdownHook = null;
        if (installShutdownHook) {
            shutdownHook = new Thread(() -> shutdown(lifecycle), "orion-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }

        try {
            lifecycle.waitForShutdown();
            return 0;
        } finally {
            removeShutdownHook(shutdownHook);
        }
    }

    private static void shutdown(OrionApplicationLifecycle lifecycle) {
        try {
            lifecycle.shutdownApplication();
        } catch (RuntimeException e) {
            log.error("Orion shutdown hook failed", e);
        }
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }
}
