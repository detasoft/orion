package pro.deta.orion.lifecycle.task;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.listener.RegisteredListener;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleTaskRegistrationTest {
    @Test
    void taskRegistrationCapturesDependenciesAndRunMode() {
        LifecycleTaskRegistration registration = new LifecycleTaskRegistration(
                ApplicationState.STARTING,
                OrionLifecycleTasks.ACL_LOAD,
                () -> OrionStageCallResult.EMPTY);

        registration.after(OrionLifecycleTasks.REPOSITORY_STORAGE)
                .before(OrionLifecycleTasks.TRANSPORTS_START)
                .runMode(LifecycleRunMode.BLOCKING);

        assertThat(registration.definition().after()).containsExactly(OrionLifecycleTasks.REPOSITORY_STORAGE);
        assertThat(registration.definition().before()).containsExactly(OrionLifecycleTasks.TRANSPORTS_START);
        assertThat(registration.definition().runMode()).isEqualTo(LifecycleRunMode.BLOCKING);
    }

    @Test
    void registrarTaskAddsExplicitLifecycleRegistration() {
        List<LifecycleTaskRegistration> registrations = new ArrayList<>();
        ApplicationStateListenerRegistrar registrar = new ApplicationStateListenerRegistrar() {
            @Override
            public RegisteredListener register(RegisteredListener listener) {
                throw new UnsupportedOperationException("legacy registration not expected");
            }

            @Override
            public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
                registrations.add(registration);
                return registration;
            }
        };
        OrionApplicationStageEventListener acl = listenerRegistrar ->
                listenerRegistrar.task(ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, () -> OrionStageCallResult.EMPTY)
                        .after(OrionLifecycleTasks.REPOSITORY_STORAGE)
                        .before(OrionLifecycleTasks.TRANSPORTS_START)
                        .runMode(LifecycleRunMode.BLOCKING);

        acl.registerToStage(registrar);

        assertThat(registrations).hasSize(1);
        LifecycleTaskDefinition definition = registrations.getFirst().definition();
        assertThat(definition.phase()).isEqualTo(ApplicationState.STARTING);
        assertThat(definition.id()).isEqualTo(OrionLifecycleTasks.ACL_LOAD);
        assertThat(definition.after()).containsExactly(OrionLifecycleTasks.REPOSITORY_STORAGE);
        assertThat(definition.before()).containsExactly(OrionLifecycleTasks.TRANSPORTS_START);
        assertThat(definition.runMode()).isEqualTo(LifecycleRunMode.BLOCKING);
    }
}
