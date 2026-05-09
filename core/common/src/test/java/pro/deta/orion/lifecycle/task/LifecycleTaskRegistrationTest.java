package pro.deta.orion.lifecycle.task;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleTaskRegistrationTest {
    private static final LifecycleTaskId STORAGE_TASK = new LifecycleTaskId("STORAGE");

    @Test
    void taskRegistrationCapturesAfterDependencies() {
        LifecycleTaskRegistration registration = new LifecycleTaskRegistration(
                ApplicationState.STARTING,
                OrionLifecycleTasks.ACL_LOAD,
                () -> OrionStageCallResult.EMPTY);

        registration.after(STORAGE_TASK);

        assertThat(registration.definition().serviceName()).isEmpty();
        assertThat(registration.definition().after()).containsExactly(STORAGE_TASK);
    }

    @Test
    void registrarTaskAddsExplicitLifecycleRegistration() {
        List<LifecycleTaskRegistration> registrations = new ArrayList<>();
        ApplicationStateListenerRegistrar registrar = new ApplicationStateListenerRegistrar() {
            @Override
            public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
                registrations.add(registration);
                return registration;
            }
        };
        OrionApplicationStageEventListener acl = listenerRegistrar ->
                listenerRegistrar.task(ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, () -> OrionStageCallResult.EMPTY)
                        .after(STORAGE_TASK);

        acl.registerToStage(registrar);

        assertThat(registrations).hasSize(1);
        LifecycleTaskDefinition definition = registrations.getFirst().definition();
        assertThat(definition.phase()).isEqualTo(ApplicationState.STARTING);
        assertThat(definition.id()).isEqualTo(OrionLifecycleTasks.ACL_LOAD);
        assertThat(definition.after()).containsExactly(STORAGE_TASK);
    }

    @Test
    void registrarTaskUsesOwnerClassNameAsServiceName() {
        List<LifecycleTaskRegistration> registrations = new ArrayList<>();
        ApplicationStateListenerRegistrar registrar = new ApplicationStateListenerRegistrar() {
            @Override
            public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
                registrations.add(registration);
                return registration;
            }
        };

        new TestLifecycleService().registerToStage(registrar);

        assertThat(registrations).hasSize(1);
        LifecycleTaskDefinition definition = registrations.getFirst().definition();
        assertThat(definition.id()).isEqualTo(OrionLifecycleTasks.ACL_LOAD);
        assertThat(definition.serviceName()).isEqualTo("TestLifecycleService");
    }

    private static final class TestLifecycleService implements OrionApplicationStageEventListener {
        @Override
        public void registerToStage(ApplicationStateListenerRegistrar registrar) {
            registrar.task(this, ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, () -> OrionStageCallResult.EMPTY);
        }
    }
}
