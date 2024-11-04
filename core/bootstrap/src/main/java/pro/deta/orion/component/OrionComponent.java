package pro.deta.orion.component;

import dagger.BindsInstance;
import dagger.Component;

import jakarta.inject.Singleton;
import pro.deta.orion.config.ConfigurationProvider;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;

@Singleton
@Component(modules = OrionRuntimeModule.class)
public interface OrionComponent {

    OrionApplicationLifecycle orionApplicationLifecycle();

    @Component.Builder
    interface Builder {
        OrionComponent build();
        @BindsInstance Builder configurationProvider(ConfigurationProvider configurationProvider);

        default Builder defaultConfigurationProvider() {
            return configurationProvider(OrionConfiguration::new);
        }
    }
}

