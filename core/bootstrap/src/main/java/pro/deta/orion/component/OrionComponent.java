package pro.deta.orion.component;

import dagger.BindsInstance;
import dagger.Component;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.schema.config.ConfigurationProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.transport.OrionTransportModule;

@Singleton
@Component(modules = {OrionRuntimeModule.class, OrionTransportModule.class})
public interface OrionComponent {

    OrionApplicationLifecycle orionApplicationLifecycle();

    OrionAccessControlServiceImpl orionAccessControlService();

    @Named("runtime")
    AggregateStateMachine runtimeStateMachine();

    @Component.Builder
    interface Builder {
        OrionComponent build();
        @BindsInstance Builder configurationProvider(ConfigurationProvider configurationProvider);

        default Builder defaultConfigurationProvider() {
            return configurationProvider(OrionConfiguration::new);
        }
    }
}
