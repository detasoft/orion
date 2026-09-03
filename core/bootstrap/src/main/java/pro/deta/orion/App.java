package pro.deta.orion;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.schema.config.ConfigurationProvider;
import pro.deta.orion.config.LocationConfigurationProvider;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.keymaterial.ServerIdentityMaterial;
import pro.deta.orion.schema.config.OrionConfiguration;

import java.io.IOException;
import java.io.PrintStream;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.function.Supplier;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

@Slf4j
public class App {
    public static void main(String[] args) throws IOException {
        int exitCode = runCommand(
                args,
                System.out,
                System.err,
                ReleaseVerifier.systemDefault(),
                OrionServiceManager::systemDefault);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runCommand(
            String[] args,
            PrintStream output,
            PrintStream errors,
            ReleaseVerifier verifier,
            Supplier<OrionServiceManager> serviceManagerSupplier
    ) throws IOException {
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

        return switch (options.command()) {
            case RUN -> runApplication(options);
            case START -> serviceManagerSupplier.get().start(options.applicationArguments(), output, errors);
            case STOP -> serviceManagerSupplier.get().stop(output, errors);
            case STATUS -> serviceManagerSupplier.get().status(output, errors);
            case RESTART -> serviceManagerSupplier.get().restart(options.applicationArguments(), output, errors);
            case VERIFY -> throw new IllegalStateException("Verify command should be handled earlier");
        };
    }

    private static int runApplication(AppOptions options) {
        ConfigurationProvider configurationProvider = configurationProvider(options);
        try {
            OrionConfiguration configuration = configurationProvider.readConfiguration();
            try (ServerIdentityMaterial serverIdentity = ServerIdentityMaterialFactory.open(
                    configuration, Map.copyOf(System.getenv()))) {
                OrionComponent orionComponent = DaggerOrionComponent.builder()
                        .configurationProvider(() -> configuration)
                        .runtimeOptions(options.runtimeOptions())
                        .serverIdentityCapability(serverIdentity)
                        .build();
                return run(orionComponent.orionApplicationLifecycle(), true);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            log.error("Cannot open configured server identity material", failure);
            return 1;
        }
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
        StateMachineDefinition.State state = lifecycle.runApplication();
        if (!RUNNING.equals(state)) {
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
