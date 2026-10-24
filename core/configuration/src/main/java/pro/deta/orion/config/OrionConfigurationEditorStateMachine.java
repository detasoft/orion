package pro.deta.orion.config;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

/** Runs configuration observation before ACL bootstrap and other configuration consumers. */
@Singleton
public final class OrionConfigurationEditorStateMachine extends ServiceLifecycleStateMachineAdapter {
    @Inject
    public OrionConfigurationEditorStateMachine(Provider<OrionConfigurationEditor> editor) {
        super("configuration", editor);
    }
}
