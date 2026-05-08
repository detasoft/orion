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
    @Test
    void taskRegistrationCapturesAfterDependencies() {
        LifecycleTaskRegistration registration = new LifecycleTaskRegistration(
                ApplicationState.STARTING,
                OrionLifecycleTasks.ACL_LOAD,
                () -> OrionStageCallResult.EMPTY);

        registration.after(OrionLifecycleTasks.REPOSITORY_STORAGE);

        assertThat(registration.definition().serviceName()).isEmpty();
        assertThat(registration.definition().after()).containsExactly(OrionLifecycleTasks.REPOSITORY_STORAGE);
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
                        .after(OrionLifecycleTasks.REPOSITORY_STORAGE);

        acl.registerToStage(registrar);

        assertThat(registrations).hasSize(1);
        LifecycleTaskDefinition definition = registrations.getFirst().definition();
        assertThat(definition.phase()).isEqualTo(ApplicationState.STARTING);
        assertThat(definition.id()).isEqualTo(OrionLifecycleTasks.ACL_LOAD);
        assertThat(definition.after()).containsExactly(OrionLifecycleTasks.REPOSITORY_STORAGE);
    }

    @Test
    void listenerTaskHelperUsesClassNameAsServiceName() {
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
            task(registrar, ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, () -> OrionStageCallResult.EMPTY);
        }
    }
}
