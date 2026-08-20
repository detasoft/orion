package pro.deta.orion.lifecycle.state;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.DISABLED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

class ServiceLifecycleStateMachineAdapterTest {
    @Test
    void enabledServiceStartsToRunningAndStopsToFinished() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        StateTransitionResult start = adapter.start();

        assertThat(start.actionResult()).isSameAs(Void.EMPTY);
        assertThat(adapter.currentState()).isEqualTo(RUNNING);
        assertThat(lifecycle.startCalls).isEqualTo(1);

        adapter.stop();

        assertThat(adapter.currentState()).isEqualTo(FIN);
        assertThat(lifecycle.stopCalls).isEqualTo(1);
    }

    @Test
    void providerIsResolvedLazily() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        AtomicBoolean resolved = new AtomicBoolean(false);
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", () -> {
            resolved.set(true);
            return lifecycle;
        });

        assertThat(adapter.currentState()).isEqualTo(NEW);
        assertThat(adapter.stateMachine().name()).isEqualTo("service");
        assertThat(resolved).isFalse();

        adapter.start();

        assertThat(adapter.currentState()).isEqualTo(RUNNING);
        assertThat(resolved).isTrue();
    }

    @Test
    void definitionExposesLeafLifecycleTransitionContract() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        assertThat(adapter.definition().availableActions(NEW))
                .isEqualTo(Set.of(adapter.startAction().id(), adapter.stopAction().id()));
        assertThat(adapter.definition().availableActions(DISABLED))
                .isEqualTo(Set.of(adapter.startAction().id(), adapter.stopAction().id()));
        assertThat(adapter.definition().availableActions(RUNNING))
                .isEqualTo(Set.of(adapter.stopAction().id()));
        assertThat(adapter.definition().availableActions(ERR))
                .isEqualTo(Set.of(adapter.stopAction().id()));
        assertThat(adapter.definition().availableActions(FIN)).isEmpty();
    }

    @Test
    void disabledServiceStartsToDisabledAfterCallingStartHook() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.enabled = false;
        lifecycle.running = false;
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        adapter.start();

        assertThat(adapter.currentState()).isEqualTo(DISABLED);
        assertThat(lifecycle.startCalls).isEqualTo(1);
    }

    @Test
    void enabledServiceThatDoesNotRunMovesToError() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.running = false;
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        adapter.start();

        assertThat(adapter.currentState()).isEqualTo(ERR);
    }

    @Test
    void startFailureMovesToErrorAndCanStillBeStopped() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startFailure = new IllegalStateException("boom");
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        assertThatThrownBy(adapter::start)
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(lifecycle.startFailure);
        assertThat(adapter.currentState()).isEqualTo(ERR);

        adapter.stop();

        assertThat(adapter.currentState()).isEqualTo(FIN);
        assertThat(lifecycle.stopCalls).isEqualTo(1);
    }

    @Test
    void stopBeforeStartFinishesWithoutCallingStopHook() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        adapter.stop();

        assertThat(adapter.currentState()).isEqualTo(FIN);
        assertThat(lifecycle.startCalls).isZero();
        assertThat(lifecycle.stopCalls).isZero();
    }

    private static final class RecordingLifecycle implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
        private boolean enabled = true;
        private boolean running = true;
        private int startCalls;
        private int stopCalls;
        private RuntimeException startFailure;

        @Override
        public void onStart() {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void onStop() {
            stopCalls++;
            running = false;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
