package pro.deta.orion.lifecycle.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachineTest {
    @Test
    void validTransitionChangesStateAndAvailableActions() {
        TestActions actions = new TestActions();
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)

                .from(TestState.RUNNING)
                .on(actions.stop())
                .to(TestState.STOPPED)
                .failTo(TestState.FAILED)

                .terminal(TestState.STOPPED)
                .build()
                .newStateMachine();

        assertThat(machine.currentState()).isEqualTo(TestState.NEW);
        assertThat(machine.availableActions()).containsExactly(actions.start());

        StateMachineEvent<TestState> event = machine.execute(actions.start(), new StartAction("first"));

        assertThat(event.from()).isEqualTo(TestState.NEW);
        assertThat(event.action()).isSameAs(actions.start());
        assertThat(event.payload()).isEqualTo(new StartAction("first"));
        assertThat(event.to()).isEqualTo(TestState.RUNNING);
        assertThat(event.failed()).isFalse();
        assertThat(machine.currentState()).isEqualTo(TestState.RUNNING);
        assertThat(machine.availableActions()).containsExactly(actions.stop());
    }

    @Test
    void invalidActionIsRejectedAndStateIsUnchanged() {
        TestActions actions = new TestActions();
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(actions.stop(), new StopAction("test")))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(machine.currentState()).isEqualTo(TestState.NEW);
    }

    @Test
    void differentBindingInstanceWithSameIdIsRejectedAndStateIsUnchanged() {
        TestActions actions = new TestActions();
        ActionBinding<StartAction> otherStart = ActionBinding.of(
                actions.start().id(),
                StartAction.class,
                action -> {
                });
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(otherStart, new StartAction("other")))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(machine.currentState()).isEqualTo(TestState.NEW);
    }

    @Test
    void duplicateTransitionDefinitionIsRejected() {
        TestActions actions = new TestActions();
        StateMachineDefinition.Builder<TestState> builder = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED);

        assertThatThrownBy(() -> builder
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.STOPPED)
                .failTo(TestState.FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failureStateIsAppliedWhenHandlerFails() {
        IllegalStateException failure = new IllegalStateException("boom");
        TestActions actions = new TestActions(action -> {
            throw failure;
        });
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)
                .build()
                .newStateMachine();
        List<StateMachineEvent<TestState>> events = new ArrayList<>();
        machine.addListener(events::add);

        StartAction payload = new StartAction("broken");
        assertThatThrownBy(() -> machine.execute(actions.start(), payload))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure);

        assertThat(machine.currentState()).isEqualTo(TestState.FAILED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().from()).isEqualTo(TestState.NEW);
        assertThat(events.getFirst().action()).isSameAs(actions.start());
        assertThat(events.getFirst().payload()).isSameAs(payload);
        assertThat(events.getFirst().to()).isEqualTo(TestState.FAILED);
        assertThat(events.getFirst().failure()).isSameAs(failure);
    }

    @Test
    void listenerReceivesSuccessfulTransition() {
        TestActions actions = new TestActions();
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)
                .build()
                .newStateMachine();
        List<StateMachineEvent<TestState>> events = new ArrayList<>();
        machine.addListener(events::add);

        machine.execute(actions.start(), new StartAction("ok"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.from()).isEqualTo(TestState.NEW);
            assertThat(event.action()).isSameAs(actions.start());
            assertThat(event.to()).isEqualTo(TestState.RUNNING);
            assertThat(event.failure()).isNull();
        });
    }

    @Test
    void terminalStateHasNoActionsUnlessTransitionIsExplicitlyConfigured() {
        TestActions actions = new TestActions();
        StateMachine<TestState> terminal = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.STOPPED)
                .failTo(TestState.FAILED)
                .terminal(TestState.STOPPED)
                .build()
                .newStateMachine();

        terminal.execute(actions.start(), new StartAction("terminal"));

        assertThat(terminal.snapshot().terminal()).isTrue();
        assertThat(terminal.availableActions()).isEmpty();

        StateMachine<TestState> restartable = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.STOPPED)
                .failTo(TestState.FAILED)

                .from(TestState.STOPPED)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)

                .terminal(TestState.STOPPED)
                .build()
                .newStateMachine();

        restartable.execute(actions.start(), new StartAction("restartable"));

        assertThat(restartable.snapshot().terminal()).isTrue();
        assertThat(restartable.availableActions()).containsExactly(actions.start());
    }

    @Test
    void availableTransitionCanBeSelectedAndExecuted() {
        TestActions actions = new TestActions();
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)
                .build()
                .newStateMachine();

        StateTransition<TestState, ?> transition = machine.availableTransitions().getFirst();

        assertThat(transition.action()).isSameAs(actions.start());
        executeRaw(machine, transition, new StartAction("selected"));
        assertThat(machine.currentState()).isEqualTo(TestState.RUNNING);
    }

    @Test
    void emptyVoidPayloadCanBeUsedForParameterlessAction() {
        List<Void> calls = new ArrayList<>();
        ActionBinding<Void> action = ActionBinding.of("test.empty", Void.class, calls::add);
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(action)
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)
                .build()
                .newStateMachine();

        StateMachineEvent<TestState> event = machine.execute(action, Void.EMPTY);

        assertThat(machine.currentState()).isEqualTo(TestState.RUNNING);
        assertThat(event.payload()).isSameAs(Void.EMPTY);
        assertThat(calls).containsExactly(Void.EMPTY);
    }

    @Test
    void concurrentActionsAreSerialized() throws Exception {
        CountDownLatch firstHandlerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        AtomicInteger handlerOrder = new AtomicInteger();
        TestActions actions = new TestActions(action -> {
            assertThat(handlerOrder.incrementAndGet()).isEqualTo(1);
            firstHandlerEntered.countDown();
            assertThat(releaseFirstHandler.await(2, TimeUnit.SECONDS)).isTrue();
        }, action -> {
            assertThat(handlerOrder.incrementAndGet()).isEqualTo(2);
        });
        StateMachine<TestState> machine = StateMachineDefinition.<TestState>startingAt(TestState.NEW)
                .from(TestState.NEW)
                .on(actions.start())
                .to(TestState.RUNNING)
                .failTo(TestState.FAILED)

                .from(TestState.RUNNING)
                .on(actions.stop())
                .to(TestState.STOPPED)
                .failTo(TestState.FAILED)
                .build()
                .newStateMachine();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StateMachineEvent<TestState>> startFuture =
                    executor.submit(() -> machine.execute(actions.start(), new StartAction("slow")));
            assertThat(firstHandlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<StateMachineEvent<TestState>> stopFuture =
                    executor.submit(() -> machine.execute(actions.stop(), new StopAction("after start")));

            Thread.sleep(100);
            assertThat(stopFuture.isDone()).isFalse();
            releaseFirstHandler.countDown();

            assertThat(startFuture.get(2, TimeUnit.SECONDS).to()).isEqualTo(TestState.RUNNING);
            assertThat(stopFuture.get(2, TimeUnit.SECONDS).to()).isEqualTo(TestState.STOPPED);
            assertThat(machine.currentState()).isEqualTo(TestState.STOPPED);
            assertThat(handlerOrder.get()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeRaw(
            StateMachine<TestState> machine,
            StateTransition<TestState, ?> transition,
            Object payload) {
        machine.execute((StateTransition) transition, payload);
    }

    private enum TestState {
        NEW,
        RUNNING,
        STOPPED,
        FAILED
    }

    private record StartAction(String request) {
    }

    private record StopAction(String reason) {
    }

    private static final class TestActions {
        private final ActionBinding<StartAction> start;
        private final ActionBinding<StopAction> stop;

        private TestActions() {
            this(action -> {
            }, action -> {
            });
        }

        private TestActions(LifecycleActionHandler<StartAction> startHandler) {
            this(startHandler, action -> {
            });
        }

        private TestActions(
                LifecycleActionHandler<StartAction> startHandler,
                LifecycleActionHandler<StopAction> stopHandler) {
            start = ActionBinding.of(StartAction.class, startHandler);
            stop = ActionBinding.of(StopAction.class, stopHandler);
        }

        private ActionBinding<StartAction> start() {
            return start;
        }

        private ActionBinding<StopAction> stop() {
            return stop;
        }
    }
}
