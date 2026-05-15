package pro.deta.orion;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.bootstrap.OrionCli;
import pro.deta.orion.bootstrap.ReleaseVerifier;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.FileConfigurationProviderImpl;

import java.io.IOException;

@Slf4j
public class App {
    public static void main(String[] args) throws IOException {
        int exitCode = new OrionCli(App::runApplication, ReleaseVerifier.systemDefault())
                .run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int runApplication() throws IOException {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(new FileConfigurationProviderImpl())
                .build();
        ApplicationState state = orionComponent.orionApplicationLifecycle().runApplication();
        if (state != ApplicationState.UP) {
            log.error("Orion startup failed with state {}", state);
            return 1;
        }
        return 0;
    }
}
