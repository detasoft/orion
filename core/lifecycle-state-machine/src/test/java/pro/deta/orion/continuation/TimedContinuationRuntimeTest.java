package pro.deta.orion.continuation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimedContinuationRuntimeTest {
    @Test
    void timeoutCanTransitionTheCurrentContinuation() {
        MutableTicker ticker = new MutableTicker(100);
        Waiting waiting = new Waiting();
        TestTimedRuntime runtime = new TestTimedRuntime(waiting, ticker);

        ticker.time = 109;
        runtime.tick();

        assertThat(runtime.currentContinuation()).isSameAs(waiting);

        ticker.time = 110;
        runtime.tick();

        assertThat(runtime.currentContinuation()).isInstanceOf(Continuation.CompletedTimeout.class);
        assertThat(runtime.terminal()).isTrue();
    }

    @Test
    void timeoutUsesCurrentContinuationAndLastInputTime() {
        MutableTicker ticker = new MutableTicker(0);
        Waiting next = new Waiting(10);
        SwitchAfterOne initial = new SwitchAfterOne(next);
        TestTimedRuntime runtime = new TestTimedRuntime(initial, ticker);

        ticker.time = 5;
        runtime.accept(new Input("a"));

        assertThat(runtime.currentContinuation()).isSameAs(next);
        assertThat(runtime.lastInputTime()).isEqualTo(5);

        ticker.time = 14;
        runtime.tick();

        assertThat(runtime.currentContinuation()).isSameAs(next);

        ticker.time = 15;
        runtime.tick();

        assertThat(runtime.currentContinuation()).isInstanceOf(Continuation.CompletedTimeout.class);
    }

    @Test
    void terminalTimeoutStopsFurtherAcceptAndTickProcessing() {
        MutableTicker ticker = new MutableTicker(0);
        CountingContinuation waiting = new CountingContinuation();
        TestTimedRuntime runtime = new TestTimedRuntime(waiting, ticker);

        ticker.time = 10;
        runtime.tick();
        runtime.accept(new Input("a"));
        ticker.time = 20;
        runtime.tick();

        assertThat(runtime.terminal()).isTrue();
        assertThat(waiting.calls).isZero();
        assertThat(runtime.currentContinuation()).isInstanceOf(Continuation.CompletedTimeout.class);
    }

    private static final class TestTimedRuntime extends ContinuationRuntime<Input> {
        private TestTimedRuntime(Continuation<Input> initial, MutableTicker ticker) {
            super(initial, ticker::nanoTime);
        }

        private Continuation<Input> currentContinuation() {
            return current();
        }

        private long lastInputTime() {
            return lastInputAtNanos();
        }
    }

    private static final class Waiting implements Timed, Continuation<Input> {
        private final long timeout;

        private Waiting() {
            this(10);
        }

        private Waiting(long timeout) {
            this.timeout = timeout;
        }

        @Override
        public ContinuationFlow<Input> process(Input input) {
            return ContinuationFlow.await();
        }

        @Override
        public long idleTimeoutNanos() {
            return timeout;
        }
    }

    private static final class SwitchAfterOne implements Timed, Continuation<Input> {
        private final Continuation<Input> next;

        private SwitchAfterOne(Continuation<Input> next) {
            this.next = next;
        }

        @Override
        public ContinuationFlow<Input> process(Input input) {
            input.read();
            return ContinuationFlow.transition(next);
        }

        @Override
        public long idleTimeoutNanos() {
            return 100;
        }
    }

    private static final class CountingContinuation implements Timed, Continuation<Input> {
        private int calls;

        @Override
        public ContinuationFlow<Input> process(Input input) {
            calls++;
            return ContinuationFlow.await();
        }

        @Override
        public long idleTimeoutNanos() {
            return 10;
        }
    }

    private static final class MutableTicker {
        private long time;

        private MutableTicker(long time) {
            this.time = time;
        }

        private long nanoTime() {
            return time;
        }
    }

    private static final class Input {
        private final String value;
        private int position;

        private Input(String value) {
            this.value = value;
        }

        private char read() {
            return value.charAt(position++);
        }
    }
}
