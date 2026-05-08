package pro.deta.orion.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.component.DaggerOrionComponent;
import pro.deta.orion.component.OrionComponent;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.test.util.ResourceUtils;
import pro.deta.orion.util.FileUtils;
import pro.deta.orion.util.NetworkUtils;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.util.LogUtils.switchAppLoggerDefault;
import static pro.deta.orion.util.LogUtils.switchAppLoggerTrace;

public class RunServerTest {
    Path markerPath = ResourceUtils.markerPathOf("integration-test");
    Path orionRoot = markerPath.getParent().resolve("RunServerTest").toAbsolutePath();

    @BeforeEach
    public void wipeOrionRoot() {
        FileUtils.wipeDirectory(orionRoot);
    }

    @Test
    public void simpleStart() {
        switchAppLoggerTrace();
        startServerWithConfig(realtimeFreePortConfiguration(orionRoot));
    }

    @Test
    public void simpleStartWithExistingConfiguration() {
        switchAppLoggerDefault();
        OrionConfiguration orionConfiguration = realtimeFreePortConfiguration(orionRoot);
        startServerWithConfig(orionConfiguration);
        System.out.println("------------------Second start------------------------");
        startServerWithConfig(orionConfiguration);
    }

    private static void startServerWithConfig(OrionConfiguration orionConfiguration) {
        OrionComponent orionComponent = DaggerOrionComponent.builder()
                .configurationProvider(() -> orionConfiguration)
                .build();
        OrionApplicationLifecycle lifecycle = orionComponent.orionApplicationLifecycle();
        ApplicationState state = lifecycle.runApplication();
        assertThat(state).isEqualTo(ApplicationState.UP);
        lifecycle.waitForStarting();
        lifecycle.beginShutdown();
        lifecycle.waitForShutdown();
    }


    private static OrionConfiguration realtimeFreePortConfiguration(Path orionRoot) {
        try {
            OrionConfiguration oc = new OrionConfiguration();
            oc.setBaseDir(orionRoot.toString());
            oc.getAccessControl().setUrl("local:orion");
            oc.getTransports().getGit().setPort(NetworkUtils.findAvailablePort());
            oc.getTransports().getSsh().setPort(NetworkUtils.findAvailablePort());
            oc.getTransports().getHttp().setPort(NetworkUtils.findAvailablePort());
            return oc;
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }

    @Test
    public void testRandomPortConfiguration() {
        OrionConfiguration oc = realtimeFreePortConfiguration(orionRoot);
    }
}
