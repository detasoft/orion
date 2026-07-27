package pro.deta.orion.lifecycle.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhaseMachineTest {
    @Test
    void replacesTheCurrentPhaseWithTheReturnedTransition() {
        RecordingPhase next = phase(false, event -> {
            throw new AssertionError("Terminal test phase must not receive another event");
        });
        RecordingPhase initial = phase(false, event -> next);
        PhaseMachine<String, RecordingPhase> machine = new PhaseMachine<>(initial);

        RecordingPhase current = machine.accept("advance");

        assertThat(current).isSameAs(next);
        assertThat(machine.phase()).isSameAs(next);
        assertThat(initial.events).containsExactly("advance");
        assertThat(initial.closeCalls).isZero();
    }

    @Test
    void rejectsNullInitialPhaseEventAndTransition() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PhaseMachine<String, RecordingPhase>(null))
                .withMessage("initialPhase");

        RecordingPhase nullTransition = phase(false, event -> null);
        PhaseMachine<String, RecordingPhase> machine = new PhaseMachine<>(nullTransition);

        assertThatNullPointerException()
                .isThrownBy(() -> machine.accept(null))
                .withMessage("event");
        assertThatNullPointerException()
                .isThrownBy(() -> machine.accept("advance"))
                .withMessage("nextPhase");
        assertThat(machine.phase()).isSameAs(nullTransition);
    }

    @Test
    void terminalPhaseRejectsFurtherEvents() {
        RecordingPhase terminal = phase(true, event -> {
            throw new AssertionError("Terminal phase must not receive events");
        });
        PhaseMachine<String, RecordingPhase> machine = new PhaseMachine<>(terminal);

        assertThat(machine.terminal()).isTrue();
        assertThatThrownBy(() -> machine.accept("unexpected"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Terminal phase does not accept events");
        assertThat(terminal.events).isEmpty();
    }

    @Test
    void closeIsIdempotentAndClosesOnlyTheCurrentPhase() {
        RecordingPhase current = phase(false, event -> {
            throw new AssertionError("Closed test phase must not receive events");
        });
        RecordingPhase previous = phase(false, event -> current);
        PhaseMachine<String, RecordingPhase> machine = new PhaseMachine<>(previous);
        machine.accept("advance");

        machine.close();
        machine.close();

        assertThat(machine.closed()).isTrue();
        assertThat(machine.phase()).isSameAs(current);
        assertThat(previous.closeCalls).isZero();
        assertThat(current.closeCalls).isOne();
        assertThatThrownBy(() -> machine.accept("unexpected"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Phase machine is closed");
    }

    private static RecordingPhase phase(
            boolean terminal,
            Function<String, RecordingPhase> transition) {
        return new RecordingPhase(terminal, transition);
    }

    private static final class RecordingPhase implements PhaseMachine.Phase<String, RecordingPhase> {
        private final boolean terminal;
        private final Function<String, RecordingPhase> transition;
        private final List<String> events = new ArrayList<>();
        private int closeCalls;

        private RecordingPhase(
                boolean terminal,
                Function<String, RecordingPhase> transition) {
            this.terminal = terminal;
            this.transition = transition;
        }

        @Override
        public RecordingPhase accept(String event) {
            events.add(event);
            return transition.apply(event);
        }

        @Override
        public boolean terminal() {
            return terminal;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
