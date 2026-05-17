package pro.deta.orion.lifecycle.state;

import org.junit.jupiter.api.Test;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;

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
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.state;

class StateMachineTest {
    private static final State RUNNING = state("RUNNING");
    private static final State STOPPED = state("STOPPED");
    private static final State FAILED = state("FAILED");

    @Test
    void newAndFinAreFixedStatesForEveryDefinition() {
        StateMachineDefinition definition = StateMachineDefinition.define().build();

        assertThat(definition.initialState()).isSameAs(NEW);
        assertThat(definition.newStateMachine().currentState()).isSameAs(NEW);
        assertThat(definition.isTerminalState(FIN)).isTrue();
        assertThat(definition.isTerminalState(NEW)).isFalse();
        assertThat(state("NEW")).isSameAs(NEW);
        assertThat(state("FIN")).isSameAs(FIN);
    }

    @Test
    void validTransitionChangesStateAndAvailableActions() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)

                .from(RUNNING)
                .on(actions.stop())
                .to(FIN)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        assertThat(machine.currentState()).isSameAs(NEW);
        assertThat(machine.availableActions()).containsExactly(actions.start());

        StateMachineEvent event = machine.execute(actions.start(), new StartAction("first"));

        assertThat(event.from()).isSameAs(NEW);
        assertThat(event.action()).isSameAs(actions.start());
        assertThat(event.payload()).isEqualTo(new StartAction("first"));
        assertThat(event.to()).isEqualTo(RUNNING);
        assertThat(event.failed()).isFalse();
        assertThat(machine.currentState()).isEqualTo(RUNNING);
        assertThat(machine.availableActions()).containsExactly(actions.stop());
    }

    @Test
    void invalidActionIsRejectedAndStateIsUnchanged() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(actions.stop(), new StopAction("test")))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(machine.currentState()).isSameAs(NEW);
    }

    @Test
    void differentBindingInstanceWithSameIdIsRejectedAndStateIsUnchanged() {
        TestActions actions = new TestActions();
        ActionBinding<StartAction> otherStart = ActionBinding.of(
                actions.start().id(),
                StartAction.class,
                action -> {
                });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(otherStart, new StartAction("other")))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(machine.currentState()).isSameAs(NEW);
    }

    @Test
    void duplicateTransitionDefinitionIsRejected() {
        TestActions actions = new TestActions();
        StateMachineDefinition.Builder builder = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED);

        assertThatThrownBy(() -> builder
                .from(NEW)
                .on(actions.start())
                .to(STOPPED)
                .failTo(FAILED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failureStateIsAppliedWhenHandlerFails() {
        IllegalStateException failure = new IllegalStateException("boom");
        TestActions actions = new TestActions(action -> {
            throw failure;
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();
        machine.addListener(events::add);

        StartAction payload = new StartAction("broken");
        assertThatThrownBy(() -> machine.execute(actions.start(), payload))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure);

        assertThat(machine.currentState()).isEqualTo(FAILED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().from()).isSameAs(NEW);
        assertThat(events.getFirst().action()).isSameAs(actions.start());
        assertThat(events.getFirst().payload()).isSameAs(payload);
        assertThat(events.getFirst().to()).isEqualTo(FAILED);
        assertThat(events.getFirst().failure()).isSameAs(failure);
    }

    @Test
    void listenerReceivesSuccessfulTransition() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();
        machine.addListener(events::add);

        machine.execute(actions.start(), new StartAction("ok"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.from()).isSameAs(NEW);
            assertThat(event.action()).isSameAs(actions.start());
            assertThat(event.to()).isEqualTo(RUNNING);
            assertThat(event.failure()).isNull();
        });
    }

    @Test
    void fixedFinStateHasNoActionsUnlessTransitionIsExplicitlyConfigured() {
        TestActions actions = new TestActions();
        StateMachine terminal = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(FIN)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        terminal.execute(actions.start(), new StartAction("terminal"));

        assertThat(terminal.snapshot().terminal()).isTrue();
        assertThat(terminal.availableActions()).isEmpty();

        StateMachine restartable = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(FIN)
                .failTo(FAILED)

                .from(FIN)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        restartable.execute(actions.start(), new StartAction("restartable"));

        assertThat(restartable.snapshot().terminal()).isTrue();
        assertThat(restartable.availableActions()).containsExactly(actions.start());
    }

    @Test
    void customTerminalStateCanStillBeDeclared() {
        TestActions actions = new TestActions();
        StateMachine terminal = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(STOPPED)
                .failTo(FAILED)
                .terminal(STOPPED)
                .build()
                .newStateMachine();

        terminal.execute(actions.start(), new StartAction("custom terminal"));

        assertThat(terminal.currentState()).isEqualTo(STOPPED);
        assertThat(terminal.snapshot().terminal()).isTrue();
    }

    @Test
    void availableTransitionCanBeSelectedAndExecuted() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        StateTransition<?> transition = machine.availableTransitions().getFirst();

        assertThat(transition.action()).isSameAs(actions.start());
        executeRaw(machine, transition, new StartAction("selected"));
        assertThat(machine.currentState()).isEqualTo(RUNNING);
    }

    @Test
    void emptyVoidPayloadCanBeUsedForParameterlessAction() {
        List<Void> calls = new ArrayList<>();
        ActionBinding<Void> action = ActionBinding.of("test.empty", Void.class, calls::add);
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(action)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        StateMachineEvent event = machine.execute(action, Void.EMPTY);

        assertThat(machine.currentState()).isEqualTo(RUNNING);
        assertThat(event.payload()).isSameAs(Void.EMPTY);
        assertThat(calls).containsExactly(Void.EMPTY);
    }

    @Test
    void describeIncludesCurrentStateAndTransitionDiagram() {
        ActionBinding<Void> start = ActionBinding.of("start", Void.class, action -> {
        });
        ActionBinding<Void> stop = ActionBinding.of("stop", Void.class, action -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)

                .from(RUNNING)
                .on(stop)
                .to(FIN)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        assertThat(machine.describe()).isEqualTo("""
                state: NEW
                in progress: <none>
                transitions:
                  NEW --start--> RUNNING (fail -> FAILED)
                  RUNNING --stop--> FIN (fail -> FAILED)""");

        machine.execute(start, Void.EMPTY);

        assertThat(machine.describe()).isEqualTo("""
                state: RUNNING
                in progress: <none>
                transitions:
                  NEW --start--> RUNNING (fail -> FAILED)
                  RUNNING --stop--> FIN (fail -> FAILED)""");
    }

    @Test
    void describeShowsTransitionInProgress() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ActionBinding<Void> start = ActionBinding.of("slow-start", Void.class, action -> {
            handlerEntered.countDown();
            assertThat(releaseHandler.await(2, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StateMachineEvent> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(machine.describe()).isEqualTo("""
                    state: NEW
                    in progress: NEW --slow-start--> RUNNING (fail -> FAILED)
                    transitions:
                      NEW --slow-start--> RUNNING (fail -> FAILED)""");

            releaseHandler.countDown();
            assertThat(future.get(2, TimeUnit.SECONDS).to()).isEqualTo(RUNNING);
        } finally {
            executor.shutdownNow();
        }

        assertThat(machine.describe()).isEqualTo("""
                state: RUNNING
                in progress: <none>
                transitions:
                  NEW --slow-start--> RUNNING (fail -> FAILED)""");
    }

    @Test
    void describeCanBePrintedBeforeStartWhileTransitioningAndWhenRunning() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ActionBinding<Void> start = ActionBinding.of("start-service", Void.class, action -> {
            handlerEntered.countDown();
            assertThat(releaseHandler.await(2, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        String configuredNotStarted = machine.describe();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        String transitioning;
        try {
            Future<StateMachineEvent> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            transitioning = machine.describe();
            releaseHandler.countDown();
            assertThat(future.get(2, TimeUnit.SECONDS).to()).isEqualTo(RUNNING);
        } finally {
            executor.shutdownNow();
        }
        String running = machine.describe();

        assertThat(configuredNotStarted).isEqualTo("""
                state: NEW
                in progress: <none>
                transitions:
                  NEW --start-service--> RUNNING (fail -> FAILED)""");
        assertThat(transitioning).isEqualTo("""
                state: NEW
                in progress: NEW --start-service--> RUNNING (fail -> FAILED)
                transitions:
                  NEW --start-service--> RUNNING (fail -> FAILED)""");
        assertThat(running).isEqualTo("""
                state: RUNNING
                in progress: <none>
                transitions:
                  NEW --start-service--> RUNNING (fail -> FAILED)""");
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
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)

                .from(RUNNING)
                .on(actions.stop())
                .to(FIN)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StateMachineEvent> startFuture =
                    executor.submit(() -> machine.execute(actions.start(), new StartAction("slow")));
            assertThat(firstHandlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<StateMachineEvent> stopFuture =
                    executor.submit(() -> machine.execute(actions.stop(), new StopAction("after start")));

            Thread.sleep(100);
            assertThat(stopFuture.isDone()).isFalse();
            releaseFirstHandler.countDown();

            assertThat(startFuture.get(2, TimeUnit.SECONDS).to()).isEqualTo(RUNNING);
            assertThat(stopFuture.get(2, TimeUnit.SECONDS).to()).isSameAs(FIN);
            assertThat(machine.currentState()).isSameAs(FIN);
            assertThat(handlerOrder.get()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeRaw(
            StateMachine machine,
            StateTransition<?> transition,
            Object payload) {
        machine.execute((StateTransition) transition, payload);
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
