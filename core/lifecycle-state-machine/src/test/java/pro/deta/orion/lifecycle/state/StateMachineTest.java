package pro.deta.orion.lifecycle.state;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.LoggerFactory;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Thread.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.AFTER_STATE_ENTERED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FAILED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_STARTED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_STARTED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.state;

class StateMachineTest {
    private static final State RUNNING = state("RUNNING");
    private static final State STOPPED = state("STOPPED");
    private static final State DISABLED = state("DISABLED");

    private enum StartOutcome {
        STARTED,
        DISABLED
    }

    @Test
    void stateTransitionResultIsNotParameterized() {
        assertThat(StateTransitionResult.class.getTypeParameters()).isEmpty();
    }

    @Test
    void stateTransitionIsNotParameterizedAndDoesNotExecuteActions() {
        assertThat(StateTransition.class.getTypeParameters()).isEmpty();
        assertThat(StateTransition.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("execute"));
    }

    @Test
    void stateMachineDoesNotExposePropagationHandlerFactories() {
        assertThat(StateMachine.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("propagateSequentialHandler"))
                .noneMatch(method -> method.getName().equals("propagateParallelHandler"))
                .noneMatch(method -> method.getName().equals("propagateSequential"))
                .noneMatch(method -> method.getName().equals("propagateParallel"));
    }

    @Test
    void structuredMonitoringViewsKeepStateNamesAndStructuredChildStates() {
        assertThat(recordComponentNames(StateMachineStatus.class))
                .containsExactly(
                        "name",
                        "state",
                        "computedState",
                        "children",
                        "availableActions",
                        "terminal");
        assertThat(Arrays.stream(StateMachine.class.getDeclaredMethods()).map(Method::getName))
                .contains("childStatuses")
                .doesNotContain("childStates", "childPhysicalStates");
        assertThat(Arrays.stream(AggregateStateMachine.class.getDeclaredMethods()).map(Method::getName))
                .contains(
                        "name",
                        "childStatuses",
                        "states",
                        "availableTransitions",
                        "status",
                        "lastTransitionResult",
                        "describe",
                        "describeStatus",
                        "subscribe")
                .doesNotContain("childStates", "childPhysicalStates");
    }

    @Test
    void stateMachinePublishesSingleStructuredStatusView() {
        assertThat(Arrays.stream(StateMachine.class.getDeclaredMethods()).map(Method::getName))
                .contains("status")
                .doesNotContain("snapshot");
        assertThatThrownBy(() -> Class.forName(
                "pro.deta.orion.lifecycle.state.StateMachineSnapshot"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void stateMachineReadsChildStatesFromChildrenWithoutCachingCopies() {
        assertThat(Arrays.stream(StateMachine.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("childStates", "observedChildStates", "childSubscriptions", "computedState");
        assertThat(AutoCloseable.class.isAssignableFrom(StateMachine.class)).isFalse();
        assertThat(AutoCloseable.class.isAssignableFrom(AggregateStateMachine.class)).isFalse();
    }

    @Test
    void aggregateRawStateMachineAccessorIsNotPublic() throws NoSuchMethodException {
        Method accessor = AggregateStateMachine.class.getDeclaredMethod("stateMachine");

        assertThat(Modifier.isPublic(accessor.getModifiers())).isFalse();
    }

    @Test
    void aggregateStateMachineExecutesStandardActionsWithoutAdapterMethods() {
        List<String> calls = new ArrayList<>();
        StateMachine child = childMachine(action(ActionId.START, ignored -> calls.add("child")));
        StateMachineDefinition definition = StateMachineDefinition.define()
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("child", child)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build();
        AggregateStateMachine aggregate = new AggregateStateMachine(definition);

        List<StateTransitionResult> results = aggregate.start();

        assertThat(calls).containsExactly("child");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.from()).isSameAs(NEW);
            assertThat(result.action()).isSameAs(ActionId.START);
            assertThat(result.actionResult()).isInstanceOf(List.class);
            assertThat(result.to()).isSameAs(RUNNING);
        });
        assertThat(child.currentState()).isSameAs(RUNNING);
        assertThat(aggregate.currentState()).isSameAs(RUNNING);
    }

    @Test
    void aggregateStateMachineExposesStructuredStatusFacade() {
        StateMachine child = childMachine(action(ActionId.START, ignored -> {
        }));
        StateMachineDefinition definition = StateMachineDefinition.define()
                .name("parent")
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("child", child)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build();
        AggregateStateMachine aggregate = new AggregateStateMachine(definition);

        StateMachineStatus status = aggregate.status();

        assertThat(status.name()).isEqualTo("parent");
        assertThat(status.state()).isSameAs(NEW);
        assertThat(status.children()).containsOnlyKeys("child");
        assertThat(status.children().get("child").state()).isSameAs(NEW);
        assertThat(aggregate.describeStatus()).isEqualTo("""
                parent: NEW
                  child: NEW""");
    }

    @Test
    void newAndFinAreFixedStatesForEveryDefinition() {
        StateMachineDefinition definition = StateMachineDefinition.define().build();

        assertThat(definition.initialState()).isSameAs(NEW);
        assertThat(definition.newStateMachine().currentState()).isSameAs(NEW);
        assertThat(definition.isTerminalState(FIN)).isTrue();
        assertThat(definition.isTerminalState(NEW)).isFalse();
        assertThat(definition.states()).containsExactly(NEW, FIN);
        assertThat(definition.newStateMachine().states()).containsExactly(NEW, FIN);
        assertThat(state("NEW")).isSameAs(NEW);
        assertThat(state("FIN")).isSameAs(FIN);
    }

    @Test
    void definitionExposesStatesUsedByTransitionsAndTerminalDeclarations() {
        TestActions actions = new TestActions();
        StateMachineDefinition definition = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .from(RUNNING)
                .on(actions.stop())
                .to(STOPPED, ERR)
                .terminal(STOPPED)
                .build();

        assertThat(definition.states()).containsExactly(NEW, RUNNING, ERR, STOPPED, FIN);
        assertThat(definition.newStateMachine().states()).containsExactly(NEW, RUNNING, ERR, STOPPED, FIN);
    }

    @Test
    void validTransitionChangesStateAndAvailableActions() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)

                .from(RUNNING)
                .on(actions.stop())
                .to(FIN, ERR)
                .build()
                .newStateMachine();

        assertThat(machine.currentState()).isSameAs(NEW);
        assertThat(machine.availableActions()).containsExactly(actions.start().id());

        StateTransitionResult event = machine.execute(actions.start(), new StartAction("first"));

        assertThat(event.from()).isSameAs(NEW);
        assertThat(event.action()).isEqualTo(actions.start().id());
        assertThat(event.payload()).isEqualTo(new StartAction("first"));
        assertThat(event.to()).isEqualTo(RUNNING);
        assertThat(event.failed()).isFalse();
        assertThat(machine.currentState()).isEqualTo(RUNNING);
        assertThat(machine.availableActions()).containsExactly(actions.stop().id());
    }

    @Test
    void customResolverChoosesStateFromAdapterStatusAndStoresLastTransitionResult() {
        AtomicReference<StartOutcome> lastOutcome = new AtomicReference<>();
        ActionBinding<Void> start =
                ActionBinding.of(ActionId.START, ignored -> {
                    StartOutcome outcome = StartOutcome.DISABLED;
                    lastOutcome.set(outcome);
                    return outcome;
                });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(DISABLED, RUNNING, ERR)
                .post(result -> result.failed() ? result.defaultState() : resolveStartOutcome(lastOutcome.get()))
                .build()
                .newStateMachine();

        StateTransitionResult result = machine.execute(start, Void.EMPTY);

        assertThat(result.from()).isSameAs(NEW);
        assertThat(result.action()).isEqualTo(ActionId.START);
        assertThat(result.payload()).isSameAs(Void.EMPTY);
        assertThat(result.actionResult()).isEqualTo(StartOutcome.DISABLED);
        assertThat(result.failure()).isNull();
        assertThat(result.targets()).containsExactly(DISABLED, RUNNING, ERR);
        assertThat(result.to()).isSameAs(DISABLED);
        assertThat(machine.currentState()).isSameAs(DISABLED);
        assertThat(machine.lastTransitionResult()).isSameAs(result);
        assertThat(machine.lastTransitionResult()).isSameAs(result);
    }

    @Test
    void defaultResolverRejectsAmbiguousSuccessTargetsWithoutCustomResolver() {
        ActionBinding<Void> start =
                ActionBinding.of(ActionId.START, ignored -> StartOutcome.STARTED);
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(DISABLED, RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(start, Void.EMPTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple success target states")
                .hasMessageContaining("START");
        StateTransitionResult result = machine.lastTransitionResult();
        assertThat(result.from()).isSameAs(NEW);
        assertThat(result.targets()).containsExactly(DISABLED, RUNNING, ERR);
        assertThat(result.actionResult()).isEqualTo(StartOutcome.STARTED);
        assertThat(result.to()).isNull();
        assertThat(machine.lastTransitionResult()).isSameAs(result);
    }

    @Test
    void failureTransitionStoresLastResultAndDescribeShowsFailure() {
        RuntimeException failure = new RuntimeException("start failed");
        ActionBinding<Void> start = action(ActionId.START, ignored -> {
            throw failure;
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(start, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasRootCause(failure);

        StateTransitionResult result = machine.lastTransitionResult();
        assertThat(result.from()).isSameAs(NEW);
        assertThat(result.to()).isSameAs(ERR);
        assertThat(result.failure()).isSameAs(failure);
        assertThat(machine.lastTransitionResult()).isSameAs(result);
        assertThat(machine.describe()).contains("last transition: NEW --START--> ERR");
        assertThat(machine.describeStatus()).contains("start failed");
    }

    @Test
    void propagationExecutesOneChildTransitionFromCurrentState() {
        AtomicInteger childCalls = new AtomicInteger();
        AtomicReference<StartOutcome> childOutcome = new AtomicReference<>();
        ActionBinding<Void> childStart = ActionBinding.of(ActionId.START, ignored -> {
            childCalls.incrementAndGet();
            StartOutcome outcome = StartOutcome.DISABLED;
            childOutcome.set(outcome);
            return outcome;
        });
        StateMachine child = StateMachineDefinition.define()
                .from(NEW, DISABLED)
                .on(childStart)
                .to(DISABLED, RUNNING, ERR)
                .post(result -> result.failed() ? result.defaultState() : resolveStartOutcome(childOutcome.get()))
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("child", child)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        parent.execute(ActionId.START, Void.EMPTY);

        assertThat(child.currentState()).isSameAs(DISABLED);
        assertThat(childCalls).hasValue(1);
    }

    @Test
    void actionIdExecutionStopsBeforeRepeatingSameBinding() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<StartOutcome> lastOutcome = new AtomicReference<>();
        ActionBinding<Void> start = ActionBinding.of(ActionId.START, ignored -> {
            calls.incrementAndGet();
            StartOutcome outcome = StartOutcome.DISABLED;
            lastOutcome.set(outcome);
            return outcome;
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW, DISABLED)
                .on(start)
                .to(DISABLED, RUNNING, ERR)
                .post(result -> result.failed() ? result.defaultState() : resolveStartOutcome(lastOutcome.get()))
                .build()
                .newStateMachine();

        List<StateTransitionResult> results = machine.execute(ActionId.START, Void.EMPTY);

        assertThat(results).hasSize(1);
        assertThat(machine.currentState()).isSameAs(DISABLED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void sameTransitionCanBeDefinedFromMultipleStates() {
        TestActions actionsFromNew = new TestActions();
        StateMachineDefinition definitionFromNew = multiStopDefinition(actionsFromNew);

        assertThat(definitionFromNew.availableActions(NEW))
                .containsExactly(actionsFromNew.start().id(), actionsFromNew.stop().id());
        assertThat(definitionFromNew.availableActions(RUNNING)).containsExactly(actionsFromNew.stop().id());

        StateMachine stoppedFromNew = definitionFromNew.newStateMachine();
        stoppedFromNew.execute(actionsFromNew.stop(), new StopAction("new"));
        assertThat(stoppedFromNew.currentState()).isEqualTo(FIN);

        TestActions actionsFromRunning = new TestActions();
        StateMachine stoppedFromRunning = multiStopDefinition(actionsFromRunning).newStateMachine();
        stoppedFromRunning.execute(actionsFromRunning.start(), new StartAction("run"));
        stoppedFromRunning.execute(actionsFromRunning.stop(), new StopAction("running"));
        assertThat(stoppedFromRunning.currentState()).isEqualTo(FIN);
    }

    @Test
    void actionIdExecutionCascadesAcrossBindingsAvailableFromSuccessiveStates() {
        State initReady = state("INIT_READY");
        State aclReady = state("ACL_READY");
        List<String> calls = new ArrayList<>();
        ActionBinding<Void> startInit = action(ActionId.START, ignored -> calls.add("init"));
        ActionBinding<Void> startAcl = action(ActionId.START, ignored -> calls.add("acl"));
        ActionBinding<Void> startTransports = action(ActionId.START, ignored -> calls.add("transports"));
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(startInit)
                .to(initReady, ERR)

                .from(initReady)
                .on(startAcl)
                .to(aclReady, ERR)

                .from(aclReady)
                .on(startTransports)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        List<StateTransitionResult> events = machine.execute(ActionId.START, Void.EMPTY);

        assertThat(calls).containsExactly("init", "acl", "transports");
        assertThat(events)
                .extracting(
                        StateTransitionResult::from,
                        StateTransitionResult::action,
                        StateTransitionResult::to)
                .containsExactly(
                        tuple(NEW, ActionId.START, initReady),
                        tuple(initReady, ActionId.START, aclReady),
                        tuple(aclReady, ActionId.START, RUNNING));
        assertThat(machine.currentState()).isEqualTo(RUNNING);
    }

    @Test
    void actionIdExecutionStopsWhenNextStateHasNoSameActionId() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)

                .from(RUNNING)
                .on(actions.stop())
                .to(FIN, ERR)
                .build()
                .newStateMachine();

        List<StateTransitionResult> events = machine.execute(actions.start().id(), new StartAction("by id"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(actions.start().id());
            assertThat(event.to()).isEqualTo(RUNNING);
        });
        assertThat(machine.currentState()).isEqualTo(RUNNING);
    }

    @Test
    void actionIdExecutionFailsWhenActionIdIsNotAvailableFromCurrentState() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(ActionId.STOP, Void.EMPTY))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Action STOP is not available from state NEW");
        assertThat(machine.currentState()).isSameAs(NEW);
    }

    @Test
    void actionIdExecutionStopsOnFailedLayer() {
        State initReady = state("INIT_READY");
        IllegalStateException failure = new IllegalStateException("acl failed");
        List<String> calls = new ArrayList<>();
        ActionBinding<Void> startInit = action(ActionId.START, ignored -> calls.add("init"));
        ActionBinding<Void> startAcl = action(ActionId.START, ignored -> {
            calls.add("acl");
            throw failure;
        });
        ActionBinding<Void> startTransports = action(ActionId.START, ignored -> calls.add("transports"));
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(startInit)
                .to(initReady, ERR)

                .from(initReady)
                .on(startAcl)
                .to(RUNNING, ERR)

                .from(RUNNING)
                .on(startTransports)
                .to(FIN, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(ActionId.START, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure);

        assertThat(calls).containsExactly("init", "acl");
        assertThat(machine.currentState()).isEqualTo(ERR);
    }

    @Test
    void actionIdLayerCanPropagateToChildMachinesInParallelAndUpdateComputedState() throws Exception {
        State initReady = state("INIT_READY");
        State childrenRunning = state("CHILDREN_RUNNING");
        CountDownLatch childHandlersEntered = new CountDownLatch(2);
        ActionBinding<Void> firstChildStart = action(ActionId.START, ignored -> {
            childHandlersEntered.countDown();
            assertThat(childHandlersEntered.await(5, TimeUnit.SECONDS)).isTrue();
        });
        ActionBinding<Void> secondChildStart = action(ActionId.START, ignored -> {
            childHandlersEntered.countDown();
            assertThat(childHandlersEntered.await(5, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine firstChild = StateMachineDefinition.define()
                .from(NEW)
                .on(firstChildStart)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine secondChild = StateMachineDefinition.define()
                .from(NEW)
                .on(secondChildStart)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        ExecutorService adapterExecutor = Executors.newFixedThreadPool(2);
        try {
            ActionBinding<Void> startAcl = action(ActionId.START, ignored -> {
            });
            StateMachine parent = StateMachineDefinition.define()
                    .childPropagationMode(StateMachineDefinition.ChildPropagationMode.PARALLEL)
                    .child("first", firstChild)
                    .child("second", secondChild)
                    .childExecutor(adapterExecutor)
                    .computedState((physicalState, childStates) -> {
                        if (childStates.values().contains(RUNNING)) {
                            return childrenRunning;
                        }
                        return physicalState;
                    })
                    .from(NEW)
                    .on(ActionId.START)
                    .to(initReady, ERR)

                    .from(initReady)
                    .on(startAcl)
                    .to(RUNNING, ERR)
                    .build()
                    .newStateMachine();

            assertThat(states(parent.childStatuses()))
                    .containsEntry("first", NEW)
                    .containsEntry("second", NEW);
            assertThat(parent.computedState()).isSameAs(NEW);

            List<StateTransitionResult> events = parent.execute(ActionId.START, Void.EMPTY);

            assertThat(firstChild.currentState()).isEqualTo(RUNNING);
            assertThat(secondChild.currentState()).isEqualTo(RUNNING);
            assertThat(parent.currentState()).isEqualTo(RUNNING);
            assertThat(states(parent.childStatuses()))
                    .containsEntry("first", RUNNING)
                    .containsEntry("second", RUNNING);
            assertThat(parent.computedState()).isEqualTo(childrenRunning);
            assertThat(parent.computedState()).isEqualTo(childrenRunning);
            assertThat(events)
                    .extracting(StateTransitionResult::from, StateTransitionResult::action, StateTransitionResult::to)
                    .containsExactly(
                            tuple(NEW, ActionId.START, initReady),
                            tuple(initReady, ActionId.START, RUNNING));
        } finally {
            adapterExecutor.shutdownNow();
        }
    }

    @Test
    void actionIdLayerCanPropagateToChildMachinesSequentiallyWithoutExecutor() {
        List<String> calls = new ArrayList<>();
        ActionBinding<Void> firstChildStart = action(ActionId.START, ignored -> calls.add("first"));
        ActionBinding<Void> secondChildStart = action(ActionId.START, ignored -> calls.add("second"));
        StateMachine firstChild = StateMachineDefinition.define()
                .from(NEW)
                .on(firstChildStart)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine secondChild = StateMachineDefinition.define()
                .from(NEW)
                .on(secondChildStart)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("first", firstChild)
                .child("second", secondChild)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        List<StateTransitionResult> events = parent.execute(ActionId.START, Void.EMPTY);

        assertThat(calls).containsExactly("first", "second");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.from()).isSameAs(NEW);
            assertThat(event.action()).isEqualTo(ActionId.START);
            assertThat(event.to()).isEqualTo(RUNNING);
        });
        assertThat(firstChild.currentState()).isEqualTo(RUNNING);
        assertThat(secondChild.currentState()).isEqualTo(RUNNING);
        assertThat(states(parent.childStatuses()))
                .containsEntry("first", RUNNING)
                .containsEntry("second", RUNNING);
    }

    @Test
    void propagatedChildMachineCanPropagateToItsChildrenSequentially() {
        List<String> calls = new ArrayList<>();
        StateMachine firstLeaf = childMachine(action(ActionId.START, ignored -> calls.add("first")));
        StateMachine secondLeaf = childMachine(action(ActionId.START, ignored -> calls.add("second")));
        StateMachine child = StateMachineDefinition.define()
                .name("child")
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("first", firstLeaf)
                .child("second", secondLeaf)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .name("parent")
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("child", child)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        List<StateTransitionResult> results = parent.execute(ActionId.START, Void.EMPTY);

        assertThat(calls).containsExactly("first", "second");
        assertThat(firstLeaf.currentState()).isSameAs(RUNNING);
        assertThat(secondLeaf.currentState()).isSameAs(RUNNING);
        assertThat(child.currentState()).isSameAs(RUNNING);
        assertThat(parent.currentState()).isSameAs(RUNNING);
        assertThat(states(child.childStatuses()))
                .containsEntry("first", RUNNING)
                .containsEntry("second", RUNNING);
        assertThat(states(parent.childStatuses())).containsEntry("child", RUNNING);
        assertThat(results).singleElement().satisfies(parentResult -> {
            StateTransitionResult childResult = (StateTransitionResult) ((List<?>) parentResult.actionResult()).getFirst();
            assertThat(childResult.to()).isSameAs(RUNNING);
            assertThat((List<?>) childResult.actionResult()).hasSize(2);
        });
    }

    @Test
    void propagatedChildMachineCanPropagateToItsChildrenInParallel() {
        CountDownLatch childHandlersEntered = new CountDownLatch(2);
        List<String> calls = new CopyOnWriteArrayList<>();
        StateMachine firstLeaf = childMachine(childStart("first", calls, childHandlersEntered));
        StateMachine secondLeaf = childMachine(childStart("second", calls, childHandlersEntered));
        ExecutorService childExecutor = Executors.newFixedThreadPool(2);
        StateMachine child = StateMachineDefinition.define()
                .name("child")
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.PARALLEL)
                .child("first", firstLeaf)
                .child("second", secondLeaf)
                .childExecutor(childExecutor)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .name("parent")
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("child", child)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        try {
            List<StateTransitionResult> results = parent.execute(ActionId.START, Void.EMPTY);

            assertThat(calls).containsExactlyInAnyOrder("first", "second");
            assertThat(firstLeaf.currentState()).isSameAs(RUNNING);
            assertThat(secondLeaf.currentState()).isSameAs(RUNNING);
            assertThat(child.currentState()).isSameAs(RUNNING);
            assertThat(parent.currentState()).isSameAs(RUNNING);
            assertThat(states(child.childStatuses()))
                    .containsEntry("first", RUNNING)
                    .containsEntry("second", RUNNING);
            assertThat(states(parent.childStatuses())).containsEntry("child", RUNNING);
            assertThat(results).singleElement().satisfies(parentResult -> {
                StateTransitionResult childResult =
                        (StateTransitionResult) ((List<?>) parentResult.actionResult()).getFirst();
                assertThat(childResult.to()).isSameAs(RUNNING);
                assertThat((List<?>) childResult.actionResult()).hasSize(2);
            });
        } finally {
            childExecutor.shutdownNow();
        }
    }

    @Test
    void statusDescriptionRendersComputedStateAndChildTree() {
        State disabled = state("DISABLED");
        ActionBinding<Void> childStart = action(ActionId.START, ignored -> {
        });
        StateMachine child = StateMachineDefinition.define()
                .name("git-native")
                .computedState((physicalState, childStates) -> disabled)
                .from(NEW)
                .on(childStart)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        StateMachine parent = StateMachineDefinition.define()
                .name("transports")
                .child("git-native", child)
                .computedState((physicalState, childStates) -> disabled)
                .build()
                .newStateMachine();

        assertThat(parent.describeStatus()).isEqualTo("""
                transports: DISABLED (state=NEW)
                  git-native: DISABLED (state=NEW)""");

    }

    @Test
    void statusTreeSeparatesPhysicalAndComputedStateForEachNode() {
        State parentComputed = state("PARENT_COMPUTED");
        State childComputed = state("CHILD_COMPUTED");
        StateMachine child = StateMachineDefinition.define()
                .name("child-machine")
                .computedState((physicalState, childStates) -> childComputed)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .name("parent-machine")
                .child("child", child)
                .computedState((physicalState, childStates) -> parentComputed)
                .build()
                .newStateMachine();

        StateMachineStatus status = parent.status();

        assertThat(status.name()).isEqualTo("parent-machine");
        assertThat(status.state()).isSameAs(NEW);
        assertThat(status.computedState()).isSameAs(parentComputed);
        assertThat(status.children()).containsOnlyKeys("child");
        StateMachineStatus childStatus = status.children().get("child");
        assertThat(childStatus.name()).isEqualTo("child");
        assertThat(childStatus.state()).isSameAs(NEW);
        assertThat(childStatus.computedState()).isSameAs(childComputed);
        assertThat(parent.childStatuses()).containsOnlyKeys("child");
        StateMachineStatus directChildStatus = parent.childStatuses().get("child");
        assertThat(directChildStatus.state()).isSameAs(NEW);
        assertThat(directChildStatus.computedState()).isSameAs(childComputed);
        assertThat(parent.childStatuses().get("child").computedState()).isSameAs(childComputed);

    }

    @Test
    void failedParallelChildPropagationMovesParentToFailureState() {
        CountDownLatch childHandlersEntered = new CountDownLatch(3);
        List<String> calls = new CopyOnWriteArrayList<>();
        ActionBinding<Void> firstChildStart = childStart("first", calls, childHandlersEntered);
        ActionBinding<Void> failingChildStart = childStart("second", calls, childHandlersEntered, () -> {
            throw new IllegalStateException("second child failed");
        });
        ActionBinding<Void> thirdChildStart = childStart("third", calls, childHandlersEntered);
        StateMachine firstChild = childMachine(firstChildStart);
        StateMachine secondChild = childMachine(failingChildStart);
        StateMachine thirdChild = childMachine(thirdChildStart);
        ExecutorService adapterExecutor = Executors.newFixedThreadPool(3);
        try {
            StateMachine parent = StateMachineDefinition.define()
                    .childPropagationMode(StateMachineDefinition.ChildPropagationMode.PARALLEL)
                    .child("first", firstChild)
                    .child("second", secondChild)
                    .child("third", thirdChild)
                    .childExecutor(adapterExecutor)
                    .from(NEW)
                    .on(ActionId.START)
                    .to(RUNNING, ERR)
                    .build()
                    .newStateMachine();

            assertThatThrownBy(() -> parent.execute(ActionId.START, Void.EMPTY))
                    .isInstanceOf(StateTransitionFailedException.class)
                    .hasRootCauseMessage("second child failed");

            assertThat(calls).containsExactlyInAnyOrder("first", "second", "third");
            assertThat(firstChild.currentState()).isEqualTo(RUNNING);
            assertThat(secondChild.currentState()).isEqualTo(ERR);
            assertThat(thirdChild.currentState()).isEqualTo(RUNNING);
            assertThat(parent.currentState()).isEqualTo(ERR);
            assertThat(states(parent.childStatuses()))
                    .containsEntry("first", RUNNING)
                    .containsEntry("second", ERR)
                    .containsEntry("third", RUNNING);
        } finally {
            adapterExecutor.shutdownNow();
        }
    }

    @Test
    void failedSequentialChildPropagationIsBestEffortWithoutRollback() {
        List<String> calls = new ArrayList<>();
        ActionBinding<Void> firstChildStart = action(ActionId.START, ignored -> calls.add("first"));
        ActionBinding<Void> failingChildStart = action(ActionId.START, ignored -> {
            calls.add("second");
            throw new IllegalStateException("second child failed");
        });
        ActionBinding<Void> thirdChildStart = action(ActionId.START, ignored -> calls.add("third"));
        ActionBinding<Void> firstChildStop = action(ActionId.STOP, ignored -> calls.add("first-stop"));
        ActionBinding<Void> thirdChildStop = action(ActionId.STOP, ignored -> calls.add("third-stop"));
        StateMachine firstChild = StateMachineDefinition.define()
                .from(NEW).on(firstChildStart).to(RUNNING, ERR)
                .from(RUNNING).on(firstChildStop).to(FIN, ERR)
                .build()
                .newStateMachine();
        StateMachine secondChild = childMachine(failingChildStart);
        StateMachine thirdChild = StateMachineDefinition.define()
                .from(NEW).on(thirdChildStart).to(RUNNING, ERR)
                .from(RUNNING).on(thirdChildStop).to(FIN, ERR)
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("first", firstChild)
                .child("second", secondChild)
                .child("third", thirdChild)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> parent.execute(ActionId.START, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasRootCauseMessage("second child failed");

        assertThat(calls).containsExactly("first", "second", "third");
        assertThat(firstChild.currentState()).isEqualTo(RUNNING);
        assertThat(secondChild.currentState()).isEqualTo(ERR);
        assertThat(thirdChild.currentState()).isEqualTo(RUNNING);
        assertThat(parent.currentState()).isEqualTo(ERR);
        assertThat(states(parent.childStatuses()))
                .containsEntry("first", RUNNING)
                .containsEntry("second", ERR)
                .containsEntry("third", RUNNING);
    }

    @Test
    void parallelPropagationRequiresChildExecutor() {
        ActionBinding<Void> childStart = action(ActionId.START, ignored -> {
        });
        StateMachine child = StateMachineDefinition.define()
                .from(NEW)
                .on(childStart)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.PARALLEL)
                .child("child", child)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> parent.execute(ActionId.START, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Parallel child action START requires childExecutor");
        assertThat(parent.currentState()).isEqualTo(ERR);
    }

    @Test
    void actionBindingExposesRegisteredStateMachine() {
        ActionBinding<Void> start = action(ActionId.START, ignored -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThat(start.stateMachine()).isSameAs(machine);
    }

    @Test
    void actionBindingCannotBeRegisteredInSeveralStateMachines() {
        ActionBinding<Void> start = action(ActionId.START, ignored -> {
        });
        StateMachineDefinition definition = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build();

        StateMachine first = definition.newStateMachine();

        assertThat(start.stateMachine()).isSameAs(first);
        assertThatThrownBy(definition::newStateMachine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Action START is already registered in another state machine");
    }

    @Test
    void unregisteredActionBindingRejectsStateMachineAccess() {
        ActionBinding<Void> start = action(ActionId.START, ignored -> {
        });

        assertThatThrownBy(start::stateMachine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Action START is not registered in a state machine");
    }

    @Test
    void invalidActionIsRejectedAndStateIsUnchanged() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(actions.stop(), new StopAction("test")))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(machine.currentState()).isSameAs(NEW);
    }

    @Test
    void differentBindingInstanceWithSameIdIsRejectedAndStateIsUnchanged() {
        TestActions actions = new TestActions();
        ActionBinding<StartAction> otherStart = action(
                actions.start().id(),
                action -> {
                });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(otherStart, new StartAction("other")))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(machine.currentState()).isSameAs(NEW);
    }

    @Test
    void duplicateTransitionDefinitionIsRejected() {
        TestActions actions = new TestActions();
        StateMachineDefinition.Builder builder = StateMachineDefinition.define();
        builder.from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR);

        assertThatThrownBy(() -> builder
                .from(NEW)
                .on(actions.start())
                .to(STOPPED, ERR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateActionIdFromSameStateIsRejected() {
        ActionBinding<Void> firstStart = action(ActionId.START, action -> {
        });
        ActionBinding<Void> secondStart = action(ActionId.START, action -> {
        });
        StateMachineDefinition.Builder builder = StateMachineDefinition.define();
        builder.from(NEW)
                .on(firstStart)
                .to(RUNNING, ERR);

        assertThatThrownBy(() -> builder
                .from(NEW)
                .on(secondStart)
                .to(STOPPED, ERR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate transition action id START for state NEW");
    }

    @Test
    void duplicateActionIdInMultiStateRuleIsRejectedWithoutPartialTransition() {
        TestActions actions = new TestActions();
        StateMachineDefinition.Builder builder = StateMachineDefinition.define();
        builder.from(RUNNING)
                .on(actions.stop())
                .to(FIN, ERR);

        assertThatThrownBy(() -> builder
                .from(NEW, RUNNING)
                .on(actions.stop())
                .to(STOPPED, ERR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate transition action id test.stop for state RUNNING");

        StateMachineDefinition definition = builder.build();
        assertThat(definition.transition(NEW, actions.stop().id())).isEmpty();
        assertThat(definition.transition(RUNNING, actions.stop().id())).isPresent();
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
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        StartAction payload = new StartAction("broken");
        assertThatThrownBy(() -> machine.execute(actions.start(), payload))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure)
                .satisfies(throwable -> {
                    StateTransitionFailedException exception = (StateTransitionFailedException) throwable;
                    StateTransitionResult result = exception.result();
                    assertThat(result.from()).isSameAs(NEW);
                    assertThat(result.action()).isEqualTo(actions.start().id());
                    assertThat(result.payload()).isSameAs(payload);
                    assertThat(result.to()).isEqualTo(ERR);
                    assertThat(result.failure()).isSameAs(failure);
                });

        assertThat(machine.currentState()).isEqualTo(ERR);
    }

    @Test
    void subscriberReceivesSuccessfulTransitionCompletion() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();
        StartAction payload = new StartAction("ok");
        machine.subscribe(events::add);

        machine.execute(actions.start(), payload);

        assertThat(events)
                .extracting(StateMachineEvent::type)
                .containsExactly(
                        TRANSITION_STARTED,
                        TRANSITION_FUNCTION_STARTED,
                        TRANSITION_FUNCTION_FINISHED,
                        AFTER_STATE_ENTERED,
                        TRANSITION_FINISHED);
        assertThat(events.getLast()).satisfies(event -> {
            assertThat(event.from()).isSameAs(NEW);
            assertThat(event.action()).isEqualTo(actions.start().id());
            assertThat(event.payload()).isSameAs(payload);
            assertThat(event.targetState()).isEqualTo(RUNNING);
            assertThat(event.currentState()).isEqualTo(RUNNING);
            assertThat(event.failure()).isNull();
        });
    }

    @Test
    void subscriberFailureDoesNotAffectTransitionOrOtherSubscribers() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        IllegalStateException subscriberFailure = new IllegalStateException("subscriber failed");
        List<StateMachineEvent> events = new ArrayList<>();
        machine.subscribe(event -> {
            throw subscriberFailure;
        });
        machine.subscribe(events::add);

        try (LogCapture logs = captureStateMachineLogs()) {
            StateTransitionResult event = machine.execute(actions.start(), new StartAction("ok"));

            assertThat(event.to()).isEqualTo(RUNNING);
            assertThat(machine.currentState()).isEqualTo(RUNNING);
            assertThat(events)
                    .extracting(StateMachineEvent::type)
                    .containsExactly(
                            TRANSITION_STARTED,
                            TRANSITION_FUNCTION_STARTED,
                            TRANSITION_FUNCTION_FINISHED,
                            AFTER_STATE_ENTERED,
                            TRANSITION_FINISHED);
            assertThat(logs.events()).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARN);
                assertThat(record.getFormattedMessage())
                        .contains("State machine subscriber failed while observing event");
                assertThat(record.getThrowableProxy().getMessage()).isEqualTo(subscriberFailure.getMessage());
            });
        }
    }

    @Test
    void subscriptionReceivesTransitionEventsThatSubscribersCanUseForTiming() {
        ActionBinding<Void> start = action("timed-start", action -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();

        machine.subscribe(events::add);
        StateTransitionResult event = machine.execute(start, Void.EMPTY);

        assertThat(events)
                .extracting(StateMachineEvent::type)
                .containsExactly(
                        TRANSITION_STARTED,
                        TRANSITION_FUNCTION_STARTED,
                        TRANSITION_FUNCTION_FINISHED,
                        AFTER_STATE_ENTERED,
                        TRANSITION_FINISHED);
        assertThat(events.getFirst().currentState()).isSameAs(NEW);
        assertThat(events.getFirst().targetState()).isEqualTo(RUNNING);
        assertThat(events.get(3).currentState()).isEqualTo(RUNNING);
        assertThat(event.to()).isEqualTo(RUNNING);
    }

    @Test
    void subscriptionShowsTransitionStartedWhileFunctionIsStillRunning() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ActionBinding<Void> start = action("observable-start", action -> {
            handlerEntered.countDown();
            assertThat(releaseHandler.await(2, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new CopyOnWriteArrayList<>();
        machine.subscribe(events::add);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StateTransitionResult> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(events)
                    .extracting(StateMachineEvent::type)
                    .containsExactly(TRANSITION_STARTED, TRANSITION_FUNCTION_STARTED);
            assertThat(events.getFirst().from()).isSameAs(NEW);
            assertThat(events.getFirst().targetState()).isEqualTo(RUNNING);
            assertThat(events.getFirst().currentState()).isSameAs(NEW);
            assertThat(events.get(1).currentState()).isSameAs(NEW);

            releaseHandler.countDown();
            StateTransitionResult event = future.get(2, TimeUnit.SECONDS);

            assertThat(event.to()).isEqualTo(RUNNING);
            assertThat(events)
                    .extracting(StateMachineEvent::type)
                    .containsExactly(
                            TRANSITION_STARTED,
                            TRANSITION_FUNCTION_STARTED,
                            TRANSITION_FUNCTION_FINISHED,
                            AFTER_STATE_ENTERED,
                            TRANSITION_FINISHED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void subscriptionReceivesFailureTimingAndFailureStateEntry() {
        IllegalStateException failure = new IllegalStateException("boom");
        ActionBinding<Void> start = action("failing-start", action -> {
            throw failure;
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();
        machine.subscribe(events::add);

        assertThatThrownBy(() -> machine.execute(start, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure)
                .satisfies(throwable -> {
                    StateTransitionFailedException exception = (StateTransitionFailedException) throwable;
                    assertThat(exception.result().failed()).isTrue();
                    assertThat(exception.result().failure()).isSameAs(failure);
                });

        assertThat(events)
                .extracting(StateMachineEvent::type)
                .containsExactly(
                        TRANSITION_STARTED,
                        TRANSITION_FUNCTION_STARTED,
                        TRANSITION_FUNCTION_FINISHED,
                        AFTER_STATE_ENTERED,
                        TRANSITION_FAILED);
        assertThat(events.get(2).failure()).isSameAs(failure);
        assertThat(events.get(3).currentState()).isEqualTo(ERR);
        assertThat(events.get(4).failure()).isSameAs(failure);
        assertThat(events.get(4)).satisfies(event -> {
            assertThat(event.failed()).isTrue();
            assertThat(event.failure()).isSameAs(failure);
            assertThat(event.currentState()).isEqualTo(ERR);
        });
    }

    @Test
    void subscriptionCanBeClosed() {
        ActionBinding<Void> start = action("start-after-close", action -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();

        StateMachineSubscription subscription = machine.subscribe(events::add);
        subscription.close();
        machine.execute(start, Void.EMPTY);

        assertThat(events).isEmpty();
    }

    @Test
    void loggingSubscriberUsesOwnerClassAndLogsTransitionTimingEvents(TestInfo testInfo) {
        ActionBinding<Void> start = action("logged-start", action -> {
            sleep(100);
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        List<String> messages = new ArrayList<>();
        String displayName = testInfo.getDisplayName();
        try (StateMachineSubscription ignored = machine.subscribe(new StateMachineLoggingSubscriber(displayName, messages::add))) {
            machine.execute(start, Void.EMPTY);
        }

        assertThat(messages).hasSize(5);
        assertThat(messages.get(0)).contains(displayName, "transition started: NEW --logged-start--> RUNNING");
        assertThat(messages.get(1)).contains(displayName, "transition function started: NEW --logged-start--> RUNNING");
        assertThat(messages.get(2)).contains(displayName, "transition function finished: NEW --logged-start--> RUNNING in PT");
        assertThat(messages.get(3)).contains(displayName, "state entered: RUNNING in PT");
        assertThat(messages.get(4)).contains(displayName, "transition finished: NEW --logged-start--> RUNNING in PT");
    }

    @Test
    void fixedFinStateHasNoActionsUnlessTransitionIsExplicitlyConfigured() {
        TestActions actions = new TestActions();
        StateMachine terminal = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(FIN, ERR)
                .build()
                .newStateMachine();

        terminal.execute(actions.start(), new StartAction("terminal"));

        assertThat(terminal.status().terminal()).isTrue();
        assertThat(terminal.availableActions()).isEmpty();

        TestActions restartableActions = new TestActions();
        StateMachine restartable = StateMachineDefinition.define()
                .from(NEW)
                .on(restartableActions.start())
                .to(FIN, ERR)

                .from(FIN)
                .on(restartableActions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        restartable.execute(restartableActions.start(), new StartAction("restartable"));

        assertThat(restartable.status().terminal()).isTrue();
        assertThat(restartable.availableActions()).containsExactly(restartableActions.start().id());
    }

    @Test
    void customTerminalStateCanStillBeDeclared() {
        TestActions actions = new TestActions();
        StateMachine terminal = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(STOPPED, ERR)
                .terminal(STOPPED)
                .build()
                .newStateMachine();

        terminal.execute(actions.start(), new StartAction("custom terminal"));

        assertThat(terminal.currentState()).isEqualTo(STOPPED);
        assertThat(terminal.status().terminal()).isTrue();
    }

    @Test
    void availableTransitionCanBeSelectedAndExecuted() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        StateTransition transition = machine.availableTransitions().getFirst();

        assertThat(transition.action()).isSameAs(actions.start());
        machine.execute(actions.start(), new StartAction("selected"));
        assertThat(machine.currentState()).isEqualTo(RUNNING);
    }

    @Test
    void emptyVoidPayloadCanBeUsedForParameterlessAction() {
        List<Void> calls = new ArrayList<>();
        ActionBinding<Void> action = action("test.empty", calls::add);
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(action)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        StateTransitionResult event = machine.execute(action, Void.EMPTY);

        assertThat(machine.currentState()).isEqualTo(RUNNING);
        assertThat(event.payload()).isSameAs(Void.EMPTY);
        assertThat(calls).containsExactly(Void.EMPTY);
    }

    @Test
    void transitionEventToStringOmitsEmptyPayloadAndMissingFailure() {
        ActionBinding<Void> action = action("test.empty", ignored -> {
        });
        StateTransitionResult event = new StateTransitionResult(NEW, action.id(), Void.EMPTY, RUNNING, null);

        assertThat(event.toString()).isEqualTo("StateTransitionResult[from=NEW, action=test.empty, to=RUNNING]");
    }

    @Test
    void transitionEventToStringIncludesNonEmptyPayloadAndFailure() {
        ActionBinding<StartAction> action = action("test.start", ignored -> {
        });
        IllegalStateException failure = new IllegalStateException("boom");
        StateTransitionResult event = new StateTransitionResult(
                NEW,
                action.id(),
                new StartAction("request"),
                ERR,
                failure);

        assertThat(event.toString()).isEqualTo(
                "StateTransitionResult[from=NEW, action=test.start, payload=StartAction[request=request], "
                        + "to=ERR, failure=java.lang.IllegalStateException: boom]");
    }

    @Test
    void describeIncludesCurrentStateAndTransitionDiagram() {
        ActionBinding<Void> start = action("start", action -> {
        });
        ActionBinding<Void> stop = action("stop", action -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)

                .from(RUNNING)
                .on(stop)
                .to(FIN, ERR)
                .build()
                .newStateMachine();

        assertThat(machine.describe()).isEqualTo("""
                state: NEW
                in progress: <none>
                last transition: <none>
                transitions:
                  NEW --start--> RUNNING (fail -> ERR)
                  RUNNING --stop--> FIN (fail -> ERR)""");

        machine.execute(start, Void.EMPTY);

        assertThat(machine.describe()).isEqualTo("""
                state: RUNNING
                in progress: <none>
                last transition: NEW --start--> RUNNING
                transitions:
                  NEW --start--> RUNNING (fail -> ERR)
                  RUNNING --stop--> FIN (fail -> ERR)""");
    }

    @Test
    void describeIncludesChildMachines() {
        ActionBinding<Void> childStart = action(ActionId.START, ignored -> {
        });
        StateMachine child = StateMachineDefinition.define()
                .from(NEW)
                .on(childStart)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
        StateMachine parent = StateMachineDefinition.define()
                .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
                .child("transport", child)
                .from(NEW)
                .on(ActionId.START)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        assertThat(parent.describe()).isEqualTo("""
                state: NEW
                in progress: <none>
                last transition: <none>
                transitions:
                  NEW --START--> RUNNING (fail -> ERR)
                children:
                  transport:
                    state: NEW
                    in progress: <none>
                    last transition: <none>
                    transitions:
                      NEW --START--> RUNNING (fail -> ERR)""");

        parent.execute(ActionId.START, Void.EMPTY);

        assertThat(parent.describe()).isEqualTo("""
                state: RUNNING
                in progress: <none>
                last transition: NEW --START--> RUNNING
                transitions:
                  NEW --START--> RUNNING (fail -> ERR)
                children:
                  transport:
                    state: RUNNING
                    in progress: <none>
                    last transition: NEW --START--> RUNNING
                    transitions:
                      NEW --START--> RUNNING (fail -> ERR)""");
    }

    @Test
    void describeShowsTransitionInProgress() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ActionBinding<Void> start = action("slow-start", action -> {
            handlerEntered.countDown();
            assertThat(releaseHandler.await(2, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StateTransitionResult> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(machine.describe()).isEqualTo("""
                    state: NEW
                    in progress: NEW --slow-start--> RUNNING (fail -> ERR)
                    last transition: <none>
                    transitions:
                      NEW --slow-start--> RUNNING (fail -> ERR)""");

            releaseHandler.countDown();
            assertThat(future.get(2, TimeUnit.SECONDS).to()).isEqualTo(RUNNING);
        } finally {
            executor.shutdownNow();
        }

        assertThat(machine.describe()).isEqualTo("""
                state: RUNNING
                in progress: <none>
                last transition: NEW --slow-start--> RUNNING
                transitions:
                  NEW --slow-start--> RUNNING (fail -> ERR)""");
    }

    @Test
    void describeCanBePrintedBeforeStartWhileTransitioningAndWhenRunning() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ActionBinding<Void> start = action("start-service", action -> {
            handlerEntered.countDown();
            assertThat(releaseHandler.await(2, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        String configuredNotStarted = machine.describe();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        String transitioning;
        try {
            Future<StateTransitionResult> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
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
                last transition: <none>
                transitions:
                  NEW --start-service--> RUNNING (fail -> ERR)""");
        assertThat(transitioning).isEqualTo("""
                state: NEW
                in progress: NEW --start-service--> RUNNING (fail -> ERR)
                last transition: <none>
                transitions:
                  NEW --start-service--> RUNNING (fail -> ERR)""");
        assertThat(running).isEqualTo("""
                state: RUNNING
                in progress: <none>
                last transition: NEW --start-service--> RUNNING
                transitions:
                  NEW --start-service--> RUNNING (fail -> ERR)""");
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
                .to(RUNNING, ERR)

                .from(RUNNING)
                .on(actions.stop())
                .to(FIN, ERR)
                .build()
                .newStateMachine();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StateTransitionResult> startFuture =
                    executor.submit(() -> machine.execute(actions.start(), new StartAction("slow")));
            assertThat(firstHandlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<StateTransitionResult> stopFuture =
                    executor.submit(() -> machine.execute(actions.stop(), new StopAction("after start")));

            sleep(100);
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

    @Test
    void concurrentSameBindingThrowsWhenStateWasAlreadyChanged() throws Exception {
        CountDownLatch firstHandlerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        AtomicInteger startCalls = new AtomicInteger();
        TestActions actions = new TestActions(action -> {
            startCalls.incrementAndGet();
            firstHandlerEntered.countDown();
            assertThat(releaseFirstHandler.await(2, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StateTransitionResult> first =
                    executor.submit(() -> machine.execute(actions.start(), new StartAction("first")));
            assertThat(firstHandlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<StateTransitionResult> second =
                    executor.submit(() -> machine.execute(actions.start(), new StartAction("second")));

            sleep(100);
            assertThat(second.isDone()).isFalse();
            releaseFirstHandler.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).to()).isSameAs(RUNNING);
            assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidStateTransitionException.class)
                    .cause()
                    .hasMessage("Action test.start is not available from state RUNNING");
            assertThat(startCalls.get()).isEqualTo(1);
            assertThat(machine.currentState()).isSameAs(RUNNING);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameActionIdThrowsWhenStateWasAlreadyChanged() throws Exception {
        CountDownLatch firstHandlerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        AtomicInteger startCalls = new AtomicInteger();
        TestActions actions = new TestActions(action -> {
            startCalls.incrementAndGet();
            firstHandlerEntered.countDown();
            assertThat(releaseFirstHandler.await(2, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<StateTransitionResult>> first =
                    executor.submit(() -> machine.execute(actions.start().id(), new StartAction("first")));
            assertThat(firstHandlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<List<StateTransitionResult>> second =
                    executor.submit(() -> machine.execute(actions.start().id(), new StartAction("second")));

            sleep(100);
            assertThat(second.isDone()).isFalse();
            releaseFirstHandler.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS))
                    .singleElement()
                    .satisfies(result -> assertThat(result.to()).isSameAs(RUNNING));
            assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidStateTransitionException.class)
                    .cause()
                    .hasMessage("Action test.start is not available from state RUNNING");
            assertThat(startCalls.get()).isEqualTo(1);
            assertThat(machine.currentState()).isSameAs(RUNNING);
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <P> ActionBinding<P> action(ActionId id, ThrowingConsumer<P> handler) {
        return ActionBinding.of(id, payload -> {
            handler.accept(payload);
            return Void.EMPTY;
        });
    }

    private static <P> ActionBinding<P> action(String id, ThrowingConsumer<P> handler) {
        return ActionBinding.of(id, payload -> {
            handler.accept(payload);
            return Void.EMPTY;
        });
    }

    private static State resolveStartOutcome(StartOutcome outcome) {
        return switch (outcome) {
            case STARTED -> RUNNING;
            case DISABLED -> DISABLED;
        };
    }

    private static ActionBinding<Void> childStart(
            String name,
            List<String> calls,
            CountDownLatch childHandlersEntered) {
        return childStart(name, calls, childHandlersEntered, () -> {
        });
    }

    private static ActionBinding<Void> childStart(
            String name,
            List<String> calls,
            CountDownLatch childHandlersEntered,
            ThrowingRunnable afterAllHandlersEntered) {
        return action(ActionId.START, ignored -> {
            calls.add(name);
            childHandlersEntered.countDown();
            assertThat(childHandlersEntered.await(5, TimeUnit.SECONDS)).isTrue();
            afterAllHandlersEntered.run();
        });
    }

    private static StateMachine childMachine(ActionBinding<Void> start) {
        return StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING, ERR)
                .build()
                .newStateMachine();
    }

    private static LogCapture captureStateMachineLogs() {
        return new LogCapture();
    }

    private static List<String> recordComponentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static Map<String, State> states(Map<String, StateMachineStatus> childStates) {
        Map<String, State> result = new LinkedHashMap<>();
        for (Map.Entry<String, StateMachineStatus> childStatus : childStates.entrySet()) {
            result.put(childStatus.getKey(), childStatus.getValue().state());
        }
        return result;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<P> {
        void accept(P payload) throws Exception;
    }

    private static final class LogCapture implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(StateMachine.class);
        private final Level previousLevel;
        private final boolean previousAdditive;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private LogCapture() {
            previousLevel = logger.getLevel();
            previousAdditive = logger.isAdditive();
            logger.setLevel(Level.WARN);
            logger.setAdditive(false);
            appender.start();
            logger.addAppender(appender);
        }

        private List<ILoggingEvent> events() {
            return appender.list;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    private record StartAction(String request) {
    }

    private record StopAction(String reason) {
    }

    private static StateMachineDefinition multiStopDefinition(TestActions actions) {
        return StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING, ERR)

                .from(NEW, RUNNING)
                .on(actions.stop())
                .to(FIN, ERR)
                .build();
    }

    private static final class TestActions {
        private final ActionBinding<StartAction> start;
        private final ActionBinding<StopAction> stop;

        private TestActions() {
            this(action -> {
            }, action -> {
            });
        }

        private TestActions(ThrowingConsumer<StartAction> startHandler) {
            this(startHandler, action -> {
            });
        }

        private TestActions(
                ThrowingConsumer<StartAction> startHandler,
                ThrowingConsumer<StopAction> stopHandler) {
            start = action("test.start", startHandler);
            stop = action("test.stop", stopHandler);
        }

        private ActionBinding<StartAction> start() {
            return start;
        }

        private ActionBinding<StopAction> stop() {
            return stop;
        }
    }
}
