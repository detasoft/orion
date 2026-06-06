package pro.deta.orion.lifecycle.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.DISABLED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

class AggregateLifecycleStateMachineAdapterTest {
    @Test
    void sequentialAggregateResolvesRunningWhenAnyChildRuns() {
        ServiceLifecycleStateMachineAdapter running = new ServiceLifecycleStateMachineAdapter(
                "running",
                new RecordingLifecycle(true, true));
        ServiceLifecycleStateMachineAdapter disabled = new ServiceLifecycleStateMachineAdapter(
                "disabled",
                new RecordingLifecycle(false, false));
        AggregateLifecycleStateMachineAdapter aggregate = AggregateLifecycleStateMachineAdapter.define("aggregate")
                .child("running", running.stateMachine())
                .child("disabled", disabled.stateMachine())
                .build();

        aggregate.start();

        assertThat(aggregate.currentState()).isEqualTo(RUNNING);
        assertThat(aggregate.childStatuses().get("running").state()).isEqualTo(RUNNING);
        assertThat(aggregate.childStatuses().get("disabled").state()).isEqualTo(DISABLED);
    }

    @Test
    void allDisabledChildrenResolveAggregateToDisabled() {
        ServiceLifecycleStateMachineAdapter first = new ServiceLifecycleStateMachineAdapter(
                "first",
                new RecordingLifecycle(false, false));
        ServiceLifecycleStateMachineAdapter second = new ServiceLifecycleStateMachineAdapter(
                "second",
                new RecordingLifecycle(false, false));
        AggregateLifecycleStateMachineAdapter aggregate = AggregateLifecycleStateMachineAdapter.define("aggregate")
                .child("first", first.stateMachine())
                .child("second", second.stateMachine())
                .build();

        aggregate.start();

        assertThat(aggregate.currentState()).isEqualTo(DISABLED);
        assertThat(aggregate.childStatuses().get("first").state()).isEqualTo(DISABLED);
        assertThat(aggregate.childStatuses().get("second").state()).isEqualTo(DISABLED);
    }

    @Test
    void childFailureResolvesAggregateToError() {
        RuntimeException failure = new RuntimeException("boom");
        ServiceLifecycleStateMachineAdapter failed = new ServiceLifecycleStateMachineAdapter(
                "failed",
                new RecordingLifecycle(true, true, failure));
        ServiceLifecycleStateMachineAdapter running = new ServiceLifecycleStateMachineAdapter(
                "running",
                new RecordingLifecycle(true, true));
        AggregateLifecycleStateMachineAdapter aggregate = AggregateLifecycleStateMachineAdapter.define("aggregate")
                .child("failed", failed.stateMachine())
                .child("running", running.stateMachine())
                .build();

        assertThatThrownBy(aggregate::start)
                .isInstanceOf(StateTransitionFailedException.class);

        assertThat(aggregate.currentState()).isEqualTo(ERR);
        assertThat(aggregate.childStatuses().get("failed").state()).isEqualTo(ERR);
        assertThat(aggregate.childStatuses().get("running").state()).isEqualTo(RUNNING);
    }

    @Test
    void stopPropagatesToChildrenAndFinishesAggregate() {
        RecordingLifecycle runningLifecycle = new RecordingLifecycle(true, true);
        RecordingLifecycle disabledLifecycle = new RecordingLifecycle(false, false);
        ServiceLifecycleStateMachineAdapter running = new ServiceLifecycleStateMachineAdapter(
                "running",
                runningLifecycle);
        ServiceLifecycleStateMachineAdapter disabled = new ServiceLifecycleStateMachineAdapter(
                "disabled",
                disabledLifecycle);
        AggregateLifecycleStateMachineAdapter aggregate = AggregateLifecycleStateMachineAdapter.define("aggregate")
                .child("running", running.stateMachine())
                .child("disabled", disabled.stateMachine())
                .build();

        aggregate.start();
        aggregate.stop();

        assertThat(aggregate.currentState()).isEqualTo(FIN);
        assertThat(aggregate.childStatuses().get("running").state()).isEqualTo(FIN);
        assertThat(aggregate.childStatuses().get("disabled").state()).isEqualTo(FIN);
        assertThat(runningLifecycle.stopCalls).isEqualTo(1);
        assertThat(disabledLifecycle.stopCalls).isZero();
    }

    private static final class RecordingLifecycle implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
        private final boolean enabled;
        private final RuntimeException startFailure;
        private boolean running;
        private int stopCalls;

        private RecordingLifecycle(boolean enabled, boolean running) {
            this(enabled, running, null);
        }

        private RecordingLifecycle(boolean enabled, boolean running, RuntimeException startFailure) {
            this.enabled = enabled;
            this.running = running;
            this.startFailure = startFailure;
        }

        @Override
        public void onStart() {
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
