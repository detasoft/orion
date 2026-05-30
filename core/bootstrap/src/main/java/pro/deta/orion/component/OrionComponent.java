package pro.deta.orion.component;

import dagger.BindsInstance;
import dagger.Component;

import jakarta.inject.Singleton;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.config.ConfigurationProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.transport.http.OrionHttpModule;

@Singleton
@Component(modules = {OrionRuntimeModule.class, OrionHttpModule.class})
public interface OrionComponent {

    OrionApplicationLifecycle orionApplicationLifecycle();

    GitRepositoryProvider gitRepositoryProvider();

    OrionAccessControlServiceImpl orionAccessControlService();

    @Component.Builder
    interface Builder {
        OrionComponent build();
        @BindsInstance Builder configurationProvider(ConfigurationProvider configurationProvider);

        default Builder defaultConfigurationProvider() {
            return configurationProvider(OrionConfiguration::new);
        }
    }
}
