package pro.deta.orion.continuation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ContinuationRuntimeTest {
    @Test
    void transitionFlowReplacesCurrentContinuationAndKeepsProcessing() {
        List<String> events = new ArrayList<>();
        ConsumeOne second = new ConsumeOne("second", events, ContinuationFlow.await());
        ConsumeOne first = new ConsumeOne("first", events, ContinuationFlow.transition(second));
        RecordingRuntime runtime = new RecordingRuntime(first);

        runtime.accept(new Input("ab"));

        assertThat(runtime.currentContinuation()).isSameAs(second);
        assertThat(events).containsExactly("first:a", "second:b");
        assertThat(runtime.observedEvents).containsExactly("transition:ConsumeOne->ConsumeOne");
    }

    @Test
    void continueFlowKeepsCurrentContinuationInTheRuntimeLoop() {
        ConsumeUntilEmpty continuation = new ConsumeUntilEmpty();
        RecordingRuntime runtime = new RecordingRuntime(continuation);

        runtime.accept(new Input("ab"));

        assertThat(runtime.currentContinuation()).isSameAs(continuation);
        assertThat(continuation.events).containsExactly('a', 'b');
    }

    @Test
    void terminalSuccessContinuationStopsRuntimeAndMakesLaterAcceptNoOp() {
        CompleteImmediately continuation = new CompleteImmediately();
        RecordingRuntime runtime = new RecordingRuntime(continuation);

        runtime.accept(new Input("a"));
        runtime.accept(new Input("b"));

        assertThat(runtime.terminal()).isTrue();
        assertThat(runtime.currentContinuation()).isInstanceOfSatisfying(
                Continuation.CompletedSuccess.class,
                success -> assertThat(success.result()).isSameAs(continuation));
        assertThat(runtime.observedEvents).containsExactly(
                "transition:CompleteImmediately->CompletedSuccess");
        assertThat(continuation.calls).isOne();
    }

    @Test
    void terminalErrorContinuationStopsRuntimeAndMakesLaterAcceptNoOp() {
        RuntimeException failure = new RuntimeException("boom");
        FailImmediately continuation = new FailImmediately(failure);
        RecordingRuntime runtime = new RecordingRuntime(continuation);

        runtime.accept(new Input("a"));
        runtime.accept(new Input("b"));

        assertThat(runtime.terminal()).isTrue();
        assertThat(runtime.currentContinuation()).isInstanceOfSatisfying(
                Continuation.CompletedError.class,
                error -> assertThat(error.error()).isSameAs(failure));
        assertThat(runtime.observedEvents).containsExactly(
                "transition:FailImmediately->CompletedError");
        assertThat(continuation.calls).isOne();
    }

    @Test
    void rejectsNullInputFlowAndTransitionTarget() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RecordingRuntime(null))
                .withMessage("initial");
        assertThatNullPointerException()
                .isThrownBy(() -> ContinuationFlow.transition(null))
                .withMessage("next");
        assertThatNullPointerException()
                .isThrownBy(() -> Continuation.completedError(null))
                .withMessage("error");

        RecordingRuntime runtime = new RecordingRuntime(input -> null);

        assertThatNullPointerException()
                .isThrownBy(() -> runtime.accept(new Input("a")))
                .withMessage("flow");
        assertThatNullPointerException()
                .isThrownBy(() -> runtime.accept(null))
                .withMessage("input");
    }

    private static final class RecordingRuntime extends ContinuationRuntime<Input> {
        private final List<String> observedEvents = new ArrayList<>();

        private RecordingRuntime(Continuation<Input> initial) {
            super(initial);
        }

        private Continuation<Input> currentContinuation() {
            return current();
        }

        @Override
        protected void onTransition(
                Continuation<Input> previous,
                Continuation<Input> next) {
            observedEvents.add("transition:" + previous.getClass().getSimpleName()
                    + "->" + next.getClass().getSimpleName());
        }

    }

    private static final class ConsumeOne implements Continuation<Input> {
        private final String name;
        private final List<String> events;
        private final ContinuationFlow<Input> flow;

        private ConsumeOne(String name, List<String> events, ContinuationFlow<Input> flow) {
            this.name = name;
            this.events = events;
            this.flow = flow;
        }

        @Override
        public ContinuationFlow<Input> process(Input input) {
            events.add(name + ":" + input.read());
            return flow;
        }
    }

    private static final class ConsumeUntilEmpty implements Continuation<Input> {
        private final List<Character> events = new ArrayList<>();

        @Override
        public ContinuationFlow<Input> process(Input input) {
            if (!input.isReadable()) {
                return ContinuationFlow.await();
            }
            events.add(input.read());
            return ContinuationFlow.continueFlow();
        }
    }

    private static final class CompleteImmediately implements Continuation<Input> {
        private int calls;

        @Override
        public ContinuationFlow<Input> process(Input input) {
            calls++;
            return ContinuationFlow.transition(Continuation.completedSuccess(this));
        }
    }

    private static final class FailImmediately implements Continuation<Input> {
        private final Throwable error;
        private int calls;

        private FailImmediately(Throwable error) {
            this.error = error;
        }

        @Override
        public ContinuationFlow<Input> process(Input input) {
            calls++;
            return ContinuationFlow.transition(Continuation.completedError(error));
        }
    }

    private static final class Input {
        private final String value;
        private int position;

        private Input(String value) {
            this.value = value;
        }

        private boolean isReadable() {
            return position < value.length();
        }

        private char read() {
            return value.charAt(position++);
        }
    }
}
