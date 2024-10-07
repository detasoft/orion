package pro.deta.orion;

import lombok.Getter;
import pro.deta.orion.config.AppConfigContext;
import pro.deta.orion.git.*;
import pro.deta.orion.settings.SettingsHolder;
import pro.deta.orion.util.OrionPathResolver;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Getter
public class OrionApp {
    private final AppConfigContext.AppConfiguration appConfiguration;
    private final Executor fixedThreadExecutor;
    private final OrionPathResolver orionPathResolver;
    private final GitRepositoryProvider gitRepositorySupplier;
    private final GitInternalService gitInternalService;
    private final SettingsHolder settingsHolder;
    private final GitNativeTransportService gitTransportService;
    private final GitSshTransportService gitSshTransportService;
    private final OrionSettingsService orionConfigurationService;
    private final AtomicReference<ApplicationState> state = new AtomicReference<>(ApplicationState.INIT);


    public OrionApp(AppConfigContext.AppConfiguration appConfiguration) {
        this.appConfiguration = appConfiguration;
        orionPathResolver = new OrionPathResolver(appConfiguration);
        fixedThreadExecutor = Executors.newFixedThreadPool(appConfiguration.getGit().getThreadPoolSize());
        gitRepositorySupplier = new GitRepositoryProviderImpl(orionPathResolver);
        gitInternalService = new GitInternalService(gitRepositorySupplier);
        settingsHolder = new SettingsHolder();
        orionConfigurationService = new OrionSettingsService(gitRepositorySupplier, orionPathResolver, settingsHolder);
        gitTransportService = new GitNativeTransportService(appConfiguration.getTransports().getGit(), gitInternalService, fixedThreadExecutor);
        gitSshTransportService = new GitSshTransportService(appConfiguration.getTransports().getSsh(), orionPathResolver, gitInternalService, fixedThreadExecutor);
    }

    public void start() {
        state.compareAndSet(ApplicationState.INIT, ApplicationState.STARTING);
        orionConfigurationService.init();

        state.compareAndSet(ApplicationState.STARTING, ApplicationState.UP);
        Thread nativeGitTransport = new Thread(() -> {
            gitTransportService.start();
        }, "Git-NativeTransport");
        nativeGitTransport.start();
        Thread gitTransport = new Thread(() -> {
            gitSshTransportService.start();
        }, "Git-SshTransport");
        gitTransport.start();
        try {
            nativeGitTransport.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
