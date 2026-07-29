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

        RuntimeFlow flow = runtime.accept(new Input("a"));
        runtime.accept(new Input("b"));

        assertThat(flow).isInstanceOf(RuntimeFlow.Terminal.class);
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

        RuntimeFlow flow = runtime.accept(new Input("a"));
        runtime.accept(new Input("b"));

        assertThat(flow).isInstanceOf(RuntimeFlow.Terminal.class);
        assertThat(runtime.terminal()).isTrue();
        assertThat(runtime.currentContinuation()).isInstanceOfSatisfying(
                Continuation.CompletedError.class,
                error -> assertThat(error.throwable()).isSameAs(failure));
        assertThat(runtime.observedEvents).containsExactly(
                "transition:FailImmediately->CompletedError");
        assertThat(continuation.calls).isOne();
    }

    @Test
    void yieldSuspendsRuntimeAndExposesTask() {
        List<String> taskLog = new ArrayList<>();
        YieldOnce continuation = new YieldOnce(() -> taskLog.add("task-ran"), ContinuationFlow.await());
        RecordingRuntime runtime = new RecordingRuntime(continuation);

        RuntimeFlow flow = runtime.accept(new Input("a"));

        assertThat(runtime.isYielding()).isTrue();
        assertThat(flow).isInstanceOf(ContinuationFlow.Yield.class);
        assertThat(taskLog).isEmpty();
    }

    @Test
    void resumeTaskRunsDriveLoopWithFrozenInput() {
        List<String> taskLog = new ArrayList<>();
        ConsumeOne after = new ConsumeOne("after", taskLog, ContinuationFlow.await());
        YieldOnce continuation = new YieldOnce(() -> taskLog.add("task-ran"), ContinuationFlow.transition(after));
        RecordingRuntime runtime = new RecordingRuntime(continuation);

        // input "a": YieldOnce does not consume it, so on resume 'after' sees the same 'a'
        RuntimeFlow flow = runtime.accept(new Input("a"));
        assertThat(runtime.isYielding()).isTrue();

        pendingTaskOf(flow).run();
        runtime.resumeTask();

        assertThat(runtime.isYielding()).isFalse();
        assertThat(taskLog).containsExactly("task-ran", "after:a");
    }

    @Test
    void transitionAndYieldSwitchesBeforeResumeAndResumesWithFrozenInput() {
        List<String> log = new ArrayList<>();
        ConsumeOne after = new ConsumeOne("after", log, ContinuationFlow.await());
        RecordingContinuation before = new RecordingContinuation(
                log,
                ContinuationFlow.transitionAndYield(
                        after,
                        () -> log.add("task-ran")));
        RecordingRuntime runtime = new RecordingRuntime(before);

        RuntimeFlow flow = runtime.accept(new Input("a"));

        assertThat(flow).isInstanceOf(ContinuationFlow.Yield.class);
        assertThat(runtime.observedEvents)
                .containsExactly("transition:RecordingContinuation->ConsumeOne");
        assertThat(log).containsExactly("closed");
        assertThat(runtime.isYielding()).isTrue();

        pendingTaskOf(flow).run();
        runtime.resumeTask();

        assertThat(runtime.isYielding()).isFalse();
        assertThat(log).containsExactly("closed", "task-ran", "after:a");
    }

    @Test
    void multipleSequentialYieldsEachRequireResumeBeforeNextAccept() {
        List<String> log = new ArrayList<>();
        YieldOnce second = new YieldOnce(() -> log.add("yield2"), ContinuationFlow.await());
        YieldOnce first = new YieldOnce(() -> log.add("yield1"), ContinuationFlow.transition(second));
        RecordingRuntime runtime = new RecordingRuntime(first);

        RuntimeFlow flow = runtime.accept(new Input("ab"));
        assertThat(runtime.isYielding()).isTrue();

        pendingTaskOf(flow).run();
        flow = runtime.resumeTask();

        assertThat(runtime.isYielding()).isTrue();
        assertThat(log).containsExactly("yield1");

        pendingTaskOf(flow).run();
        runtime.resumeTask();

        assertThat(runtime.isYielding()).isFalse();
        assertThat(log).containsExactly("yield1", "yield2");
    }

    @Test
    void acceptReturnsErrorWhenYieldIsPending() {
        YieldOnce continuation = new YieldOnce(() -> {}, ContinuationFlow.await());
        RecordingRuntime runtime = new RecordingRuntime(continuation);

        runtime.accept(new Input("a"));
        assertThat(runtime.isYielding()).isTrue();

        assertThat(runtime.accept(new Input("b")))
                .isInstanceOfSatisfying(RuntimeFlow.Error.class, error -> {
                    assertThat(error.message())
                            .isEqualTo("Cannot accept new input while a Yield task is pending");
                    assertThat(error.throwable()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void resumeTaskReturnsErrorWhenNoYieldIsPending() {
        RecordingRuntime runtime = new RecordingRuntime(input -> ContinuationFlow.await());

        assertThat(runtime.resumeTask())
                .isInstanceOfSatisfying(RuntimeFlow.Error.class, error -> {
                    assertThat(error.message()).isEqualTo("No pending Yield task to resume");
                    assertThat(error.throwable()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void closeDoesNotCloseAlreadyTerminalContinuation() {
        CountingTerminal continuation = new CountingTerminal();
        RecordingRuntime runtime = new RecordingRuntime(continuation);

        runtime.close("first");
        runtime.close("second");

        assertThat(continuation.closeCalls).isZero();
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
                .isThrownBy(() -> Continuation.completedError(null, new RuntimeException()))
                .withMessage("message");
        assertThatNullPointerException()
                .isThrownBy(() -> Continuation.completedError("boom", null))
                .withMessage("throwable");

        RecordingRuntime runtime = new RecordingRuntime(input -> null);

        assertThatNullPointerException()
                .isThrownBy(() -> runtime.accept(new Input("a")))
                .withMessage("flow");
        assertThat(runtime.accept(null))
                .isInstanceOfSatisfying(RuntimeFlow.Error.class, error -> {
                    assertThat(error.message()).isEqualTo("Continuation input must not be null");
                    assertThat(error.throwable()).isInstanceOf(IllegalStateException.class);
                });
    }

    private static Runnable pendingTaskOf(RuntimeFlow flow) {
        return ((ContinuationFlow.Yield<Input>) flow).task();
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

    private static final class RecordingContinuation implements Continuation<Input> {
        private final List<String> events;
        private final ContinuationFlow<Input> flow;

        private RecordingContinuation(
                List<String> events,
                ContinuationFlow<Input> flow) {
            this.events = events;
            this.flow = flow;
        }

        @Override
        public ContinuationFlow<Input> process(Input input) {
            return flow;
        }

        @Override
        public void close() {
            events.add("closed");
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
            return ContinuationFlow.transition(Continuation.completedError("boom", error));
        }
    }

    /** Yields once, then transitions to {@code next} on resume. */
    private static final class YieldOnce implements Continuation<Input> {
        private final Runnable task;
        private final ContinuationFlow<Input> afterYield;
        private boolean yielded;

        private YieldOnce(Runnable task, ContinuationFlow<Input> afterYield) {
            this.task = task;
            this.afterYield = afterYield;
        }

        @Override
        public ContinuationFlow<Input> process(Input input) {
            if (!yielded) {
                yielded = true;
                return ContinuationFlow.yield(task);
            }
            return afterYield;
        }
    }

    private static final class CountingTerminal
            implements Continuation.TerminalContinuation<Input> {
        private int closeCalls;

        @Override
        public void close() {
            closeCalls++;
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
