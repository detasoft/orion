package pro.deta.orion;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.FileConfigurationProviderImpl;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;

import java.io.IOException;

@Slf4j
public class App {
    public static void main(String[] args) throws IOException {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(new FileConfigurationProviderImpl())
                .build();
        int exitCode = run(orionComponent.orionApplicationLifecycle(), true);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
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
