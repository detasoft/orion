package pro.deta.orion.lifecycle.state;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.LoggerFactory;
import pro.deta.orion.lifecycle.state.StateMachineDefinition.State;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Thread.sleep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.state;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.AFTER_STATE_ENTERED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FAILED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_STARTED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_STARTED;

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
                .to(RUNNING)
                .failTo(FAILED)
                .from(RUNNING)
                .on(actions.stop())
                .to(STOPPED)
                .failTo(FAILED)
                .terminal(STOPPED)
                .build();

        assertThat(definition.states()).containsExactly(NEW, RUNNING, FAILED, STOPPED, FIN);
        assertThat(definition.newStateMachine().states()).containsExactly(NEW, RUNNING, FAILED, STOPPED, FIN);
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
        assertThat(machine.availableActions()).containsExactly(actions.start().id());

        StateTransitionEvent event = machine.execute(actions.start(), new StartAction("first"));

        assertThat(event.from()).isSameAs(NEW);
        assertThat(event.action()).isEqualTo(actions.start().id());
        assertThat(event.payload()).isEqualTo(new StartAction("first"));
        assertThat(event.to()).isEqualTo(RUNNING);
        assertThat(event.failed()).isFalse();
        assertThat(machine.currentState()).isEqualTo(RUNNING);
        assertThat(machine.availableActions()).containsExactly(actions.stop().id());
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
        ActionBinding<Void> startInit = ActionBinding.of(ActionId.START, ignored -> calls.add("init"));
        ActionBinding<Void> startAcl = ActionBinding.of(ActionId.START, ignored -> calls.add("acl"));
        ActionBinding<Void> startTransports = ActionBinding.of(ActionId.START, ignored -> calls.add("transports"));
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(startInit)
                .to(initReady)
                .failTo(FAILED)

                .from(initReady)
                .on(startAcl)
                .to(aclReady)
                .failTo(FAILED)

                .from(aclReady)
                .on(startTransports)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        List<StateTransitionEvent> events = machine.execute(ActionId.START, Void.EMPTY);

        assertThat(calls).containsExactly("init", "acl", "transports");
        assertThat(events)
                .extracting(
                        StateTransitionEvent::from,
                        StateTransitionEvent::action,
                        StateTransitionEvent::to)
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
                .to(RUNNING)
                .failTo(FAILED)

                .from(RUNNING)
                .on(actions.stop())
                .to(FIN)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        List<StateTransitionEvent> events = machine.execute(actions.start().id(), new StartAction("by id"));

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
                .to(RUNNING)
                .failTo(FAILED)
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
        ActionBinding<Void> startInit = ActionBinding.of(ActionId.START, ignored -> calls.add("init"));
        ActionBinding<Void> startAcl = ActionBinding.of(ActionId.START, ignored -> {
            calls.add("acl");
            throw failure;
        });
        ActionBinding<Void> startTransports = ActionBinding.of(ActionId.START, ignored -> calls.add("transports"));
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(startInit)
                .to(initReady)
                .failTo(FAILED)

                .from(initReady)
                .on(startAcl)
                .to(RUNNING)
                .failTo(FAILED)

                .from(RUNNING)
                .on(startTransports)
                .to(FIN)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> machine.execute(ActionId.START, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure);

        assertThat(calls).containsExactly("init", "acl");
        assertThat(machine.currentState()).isEqualTo(FAILED);
    }

    @Test
    void actionIdLayerCanPropagateToChildMachinesInParallelAndUpdateComputedState() throws Exception {
        State initReady = state("INIT_READY");
        State childrenRunning = state("CHILDREN_RUNNING");
        CountDownLatch childHandlersEntered = new CountDownLatch(2);
        ActionBinding<Void> firstChildStart = ActionBinding.of(ActionId.START, ignored -> {
            childHandlersEntered.countDown();
            assertThat(childHandlersEntered.await(5, TimeUnit.SECONDS)).isTrue();
        });
        ActionBinding<Void> secondChildStart = ActionBinding.of(ActionId.START, ignored -> {
            childHandlersEntered.countDown();
            assertThat(childHandlersEntered.await(5, TimeUnit.SECONDS)).isTrue();
        });
        StateMachine firstChild = StateMachineDefinition.define()
                .from(NEW)
                .on(firstChildStart)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        StateMachine secondChild = StateMachineDefinition.define()
                .from(NEW)
                .on(secondChildStart)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        ExecutorService adapterExecutor = Executors.newFixedThreadPool(2);
        try {
            ActionBinding<Void> propagateStart = propagatingAction(ActionId.START, true);
            ActionBinding<Void> startAcl = ActionBinding.of(ActionId.START, ignored -> {
            });
            StateMachine parent = StateMachineDefinition.define()
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
                    .on(propagateStart)
                    .to(initReady)
                    .failTo(FAILED)

                    .from(initReady)
                    .on(startAcl)
                    .to(RUNNING)
                    .failTo(FAILED)
                    .build()
                    .newStateMachine();

            assertThat(parent.childStates())
                    .containsEntry("first", NEW)
                    .containsEntry("second", NEW);
            assertThat(parent.computedState()).isSameAs(NEW);

            List<StateTransitionEvent> events = parent.execute(ActionId.START, Void.EMPTY);

            assertThat(firstChild.currentState()).isEqualTo(RUNNING);
            assertThat(secondChild.currentState()).isEqualTo(RUNNING);
            assertThat(parent.currentState()).isEqualTo(RUNNING);
            assertThat(parent.childStates())
                    .containsEntry("first", RUNNING)
                    .containsEntry("second", RUNNING);
            assertThat(parent.computedState()).isEqualTo(childrenRunning);
            assertThat(parent.snapshot().computedState()).isEqualTo(childrenRunning);
            assertThat(events)
                    .extracting(StateTransitionEvent::from, StateTransitionEvent::action, StateTransitionEvent::to)
                    .containsExactly(
                            tuple(NEW, ActionId.START, initReady),
                            tuple(initReady, ActionId.START, RUNNING));
            parent.close();
        } finally {
            adapterExecutor.shutdownNow();
        }
    }

    @Test
    void actionIdLayerCanPropagateToChildMachinesSequentiallyWithoutExecutor() {
        List<String> calls = new ArrayList<>();
        ActionBinding<Void> firstChildStart = ActionBinding.of(ActionId.START, ignored -> calls.add("first"));
        ActionBinding<Void> secondChildStart = ActionBinding.of(ActionId.START, ignored -> calls.add("second"));
        StateMachine firstChild = StateMachineDefinition.define()
                .from(NEW)
                .on(firstChildStart)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        StateMachine secondChild = StateMachineDefinition.define()
                .from(NEW)
                .on(secondChildStart)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        ActionBinding<Void> propagateStart = propagatingAction(ActionId.START, false);
        StateMachine parent = StateMachineDefinition.define()
                .child("first", firstChild)
                .child("second", secondChild)
                .from(NEW)
                .on(propagateStart)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        List<StateTransitionEvent> events = parent.execute(ActionId.START, Void.EMPTY);

        assertThat(calls).containsExactly("first", "second");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.from()).isSameAs(NEW);
            assertThat(event.action()).isEqualTo(ActionId.START);
            assertThat(event.to()).isEqualTo(RUNNING);
        });
        assertThat(firstChild.currentState()).isEqualTo(RUNNING);
        assertThat(secondChild.currentState()).isEqualTo(RUNNING);
        assertThat(parent.childStates())
                .containsEntry("first", RUNNING)
                .containsEntry("second", RUNNING);
        parent.close();
    }

    @Test
    void actionBindingControlsPropagationOrder() {
        List<String> calls = new ArrayList<>();
        ActionBinding<Void> childStart = ActionBinding.of(ActionId.START, ignored -> calls.add("child"));
        StateMachine child = childMachine(childStart);
        OrderedParentAction parentAction = new OrderedParentAction(calls);
        StateMachine parent = StateMachineDefinition.define()
                .child("child", child)
                .from(NEW)
                .on(parentAction.start())
                .to(RUNNING)
                .failTo(ERR)
                .build()
                .newStateMachine();

        parent.execute(ActionId.START, Void.EMPTY);

        assertThat(calls).containsExactly("parent-before", "child", "parent-after");
        assertThat(child.currentState()).isEqualTo(RUNNING);
        assertThat(parent.currentState()).isEqualTo(RUNNING);
        parent.close();
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
            ActionBinding<Void> propagateStart = propagatingAction(ActionId.START, true);
            StateMachine parent = StateMachineDefinition.define()
                    .child("first", firstChild)
                    .child("second", secondChild)
                    .child("third", thirdChild)
                    .childExecutor(adapterExecutor)
                    .from(NEW)
                    .on(propagateStart)
                    .to(RUNNING)
                    .failTo(ERR)
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
            assertThat(parent.childStates())
                    .containsEntry("first", RUNNING)
                    .containsEntry("second", ERR)
                    .containsEntry("third", RUNNING);
            parent.close();
        } finally {
            adapterExecutor.shutdownNow();
        }
    }

    @Test
    void failedSequentialChildPropagationMovesParentToFailureStateAfterAllChildrenFinish() {
        List<String> calls = new ArrayList<>();
        ActionBinding<Void> firstChildStart = ActionBinding.of(ActionId.START, ignored -> calls.add("first"));
        ActionBinding<Void> failingChildStart = ActionBinding.of(ActionId.START, ignored -> {
            calls.add("second");
            throw new IllegalStateException("second child failed");
        });
        ActionBinding<Void> thirdChildStart = ActionBinding.of(ActionId.START, ignored -> calls.add("third"));
        StateMachine firstChild = childMachine(firstChildStart);
        StateMachine secondChild = childMachine(failingChildStart);
        StateMachine thirdChild = childMachine(thirdChildStart);
        ActionBinding<Void> propagateStart = propagatingAction(ActionId.START, false);
        StateMachine parent = StateMachineDefinition.define()
                .child("first", firstChild)
                .child("second", secondChild)
                .child("third", thirdChild)
                .from(NEW)
                .on(propagateStart)
                .to(RUNNING)
                .failTo(ERR)
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
        assertThat(parent.childStates())
                .containsEntry("first", RUNNING)
                .containsEntry("second", ERR)
                .containsEntry("third", RUNNING);
        parent.close();
    }

    @Test
    void parallelPropagationRequiresChildExecutor() {
        ActionBinding<Void> childStart = ActionBinding.of(ActionId.START, ignored -> {
        });
        StateMachine child = StateMachineDefinition.define()
                .from(NEW)
                .on(childStart)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        ActionBinding<Void> propagateStart = propagatingAction(ActionId.START, true);
        StateMachine parent = StateMachineDefinition.define()
                .child("child", child)
                .from(NEW)
                .on(propagateStart)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        assertThatThrownBy(() -> parent.execute(ActionId.START, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Parallel child action START requires childExecutor");
        assertThat(parent.currentState()).isEqualTo(FAILED);
    }

    @Test
    void actionBindingExposesRegisteredStateMachine() {
        ActionBinding<Void> start = ActionBinding.of(ActionId.START, ignored -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(ERR)
                .build()
                .newStateMachine();

        assertThat(start.stateMachine()).isSameAs(machine);
    }

    @Test
    void actionBindingCannotBeRegisteredInSeveralStateMachines() {
        ActionBinding<Void> start = ActionBinding.of(ActionId.START, ignored -> {
        });
        StateMachineDefinition definition = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(ERR)
                .build();

        StateMachine first = definition.newStateMachine();

        assertThat(start.stateMachine()).isSameAs(first);
        assertThatThrownBy(definition::newStateMachine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Action START is already registered in another state machine");
    }

    @Test
    void unregisteredActionBindingRejectsStateMachineAccess() {
        ActionBinding<Void> start = ActionBinding.of(ActionId.START, ignored -> {
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
    void duplicateActionIdFromSameStateIsRejected() {
        ActionBinding<Void> firstStart = ActionBinding.of(ActionId.START, action -> {
        });
        ActionBinding<Void> secondStart = ActionBinding.of(ActionId.START, action -> {
        });
        StateMachineDefinition.Builder builder = StateMachineDefinition.define()
                .from(NEW)
                .on(firstStart)
                .to(RUNNING)
                .failTo(FAILED);

        assertThatThrownBy(() -> builder
                .from(NEW)
                .on(secondStart)
                .to(STOPPED)
                .failTo(FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate transition action id START for state NEW");
    }

    @Test
    void duplicateActionIdInMultiStateRuleIsRejectedWithoutPartialTransition() {
        TestActions actions = new TestActions();
        StateMachineDefinition.Builder builder = StateMachineDefinition.define()
                .from(RUNNING)
                .on(actions.stop())
                .to(FIN)
                .failTo(FAILED);

        assertThatThrownBy(() -> builder
                .from(NEW, RUNNING)
                .on(actions.stop())
                .to(STOPPED)
                .failTo(FAILED))
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
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        List<StateTransitionEvent> events = new ArrayList<>();
        machine.addListener(events::add);

        StartAction payload = new StartAction("broken");
        assertThatThrownBy(() -> machine.execute(actions.start(), payload))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure);

        assertThat(machine.currentState()).isEqualTo(FAILED);
        assertThat(events).hasSize(1);
        StateTransitionEvent firstEvent = events.getFirst();
        assertThat(firstEvent.from()).isSameAs(NEW);
        assertThat(firstEvent.action()).isEqualTo(actions.start().id());
        assertThat(firstEvent.payload()).isSameAs(payload);
        assertThat(firstEvent.to()).isEqualTo(FAILED);
        assertThat(firstEvent.failure()).isSameAs(failure);
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
        List<StateTransitionEvent> events = new ArrayList<>();
        machine.addListener(events::add);

        machine.execute(actions.start(), new StartAction("ok"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.from()).isSameAs(NEW);
            assertThat(event.action()).isEqualTo(actions.start().id());
            assertThat(event.to()).isEqualTo(RUNNING);
            assertThat(event.failure()).isNull();
        });
    }

    @Test
    void listenerFailureDoesNotAffectTransitionOrOtherObservers() {
        TestActions actions = new TestActions();
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(actions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        IllegalStateException listenerFailure = new IllegalStateException("listener failed");
        List<StateTransitionEvent> transitionEvents = new ArrayList<>();
        List<StateMachineEvent> machineEvents = new ArrayList<>();
        machine.addListener(event -> {
            throw listenerFailure;
        });
        machine.addListener(transitionEvents::add);
        machine.subscribe(machineEvents::add);

        try (LogCapture logs = captureStateMachineLogs()) {
            StateTransitionEvent event = machine.execute(actions.start(), new StartAction("ok"));

            assertThat(event.to()).isEqualTo(RUNNING);
            assertThat(machine.currentState()).isEqualTo(RUNNING);
            assertThat(transitionEvents).singleElement().satisfies(observed -> assertThat(observed.to()).isEqualTo(RUNNING));
            assertThat(machineEvents)
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
                        .contains("State machine listener failed while observing transition");
                assertThat(record.getThrowableProxy().getMessage()).isEqualTo(listenerFailure.getMessage());
            });
        }
    }

    @Test
    void subscriptionReceivesTransitionEventsThatSubscribersCanUseForTiming() {
        ActionBinding<Void> start = ActionBinding.of("timed-start", action -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();

        machine.subscribe(events::add);
        StateTransitionEvent event = machine.execute(start, Void.EMPTY);

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
        ActionBinding<Void> start = ActionBinding.of("observable-start", action -> {
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
        List<StateMachineEvent> events = new CopyOnWriteArrayList<>();
        machine.subscribe(events::add);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<StateTransitionEvent> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(events)
                    .extracting(StateMachineEvent::type)
                    .containsExactly(TRANSITION_STARTED, TRANSITION_FUNCTION_STARTED);
            assertThat(events.getFirst().from()).isSameAs(NEW);
            assertThat(events.getFirst().targetState()).isEqualTo(RUNNING);
            assertThat(events.getFirst().currentState()).isSameAs(NEW);
            assertThat(events.get(1).currentState()).isSameAs(NEW);

            releaseHandler.countDown();
            StateTransitionEvent event = future.get(2, TimeUnit.SECONDS);

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
        ActionBinding<Void> start = ActionBinding.of("failing-start", action -> {
            throw failure;
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();
        List<StateTransitionEvent> completedEvents = new ArrayList<>();
        machine.subscribe(events::add);
        machine.addListener(completedEvents::add);

        assertThatThrownBy(() -> machine.execute(start, Void.EMPTY))
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(failure);

        assertThat(events)
                .extracting(StateMachineEvent::type)
                .containsExactly(
                        TRANSITION_STARTED,
                        TRANSITION_FUNCTION_STARTED,
                        TRANSITION_FUNCTION_FINISHED,
                        AFTER_STATE_ENTERED,
                        TRANSITION_FAILED);
        assertThat(events.get(2).failure()).isSameAs(failure);
        assertThat(events.get(3).currentState()).isEqualTo(FAILED);
        assertThat(events.get(4).failure()).isSameAs(failure);
        assertThat(completedEvents).singleElement().satisfies(event -> {
            assertThat(event.failed()).isTrue();
            assertThat(event.failure()).isSameAs(failure);
        });
    }

    @Test
    void subscriptionCanBeClosed() {
        ActionBinding<Void> start = ActionBinding.of("start-after-close", action -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        List<StateMachineEvent> events = new ArrayList<>();

        StateMachineSubscription subscription = machine.subscribe(events::add);
        subscription.close();
        machine.execute(start, Void.EMPTY);

        assertThat(events).isEmpty();
    }

    @Test
    void subscriberFailureDoesNotAffectTransition() {
        ActionBinding<Void> start = ActionBinding.of("start-with-broken-subscriber", action -> {
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();
        IllegalStateException subscriberFailure = new IllegalStateException("subscriber failed");
        machine.subscribe(event -> {
            throw subscriberFailure;
        });

        try (LogCapture logs = captureStateMachineLogs()) {
            StateTransitionEvent event = machine.execute(start, Void.EMPTY);

            assertThat(event.to()).isEqualTo(RUNNING);
            assertThat(event.failed()).isFalse();
            assertThat(machine.currentState()).isEqualTo(RUNNING);
            assertThat(logs.events()).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARN);
                assertThat(record.getFormattedMessage())
                        .contains("State machine subscriber failed while observing event");
                assertThat(record.getThrowableProxy().getMessage()).isEqualTo(subscriberFailure.getMessage());
            });
        }
    }

    @Test
    void loggingSubscriberUsesOwnerClassAndLogsTransitionTimingEvents(TestInfo testInfo) {
        ActionBinding<Void> start = ActionBinding.of("logged-start", action -> {
            sleep(100);
        });
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(start)
                .to(RUNNING)
                .failTo(FAILED)
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
                .to(FIN)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        terminal.execute(actions.start(), new StartAction("terminal"));

        assertThat(terminal.snapshot().terminal()).isTrue();
        assertThat(terminal.availableActions()).isEmpty();

        TestActions restartableActions = new TestActions();
        StateMachine restartable = StateMachineDefinition.define()
                .from(NEW)
                .on(restartableActions.start())
                .to(FIN)
                .failTo(FAILED)

                .from(FIN)
                .on(restartableActions.start())
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        restartable.execute(restartableActions.start(), new StartAction("restartable"));

        assertThat(restartable.snapshot().terminal()).isTrue();
        assertThat(restartable.availableActions()).containsExactly(restartableActions.start().id());
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
        ActionBinding<Void> action = ActionBinding.of("test.empty", calls::add);
        StateMachine machine = StateMachineDefinition.define()
                .from(NEW)
                .on(action)
                .to(RUNNING)
                .failTo(FAILED)
                .build()
                .newStateMachine();

        StateTransitionEvent event = machine.execute(action, Void.EMPTY);

        assertThat(machine.currentState()).isEqualTo(RUNNING);
        assertThat(event.payload()).isSameAs(Void.EMPTY);
        assertThat(calls).containsExactly(Void.EMPTY);
    }

    @Test
    void transitionEventToStringOmitsEmptyPayloadAndMissingFailure() {
        ActionBinding<Void> action = ActionBinding.of("test.empty", ignored -> {
        });
        StateTransitionEvent event = new StateTransitionEvent(NEW, action.id(), Void.EMPTY, RUNNING, null);

        assertThat(event.toString()).isEqualTo("StateTransitionEvent[from=NEW, action=test.empty, to=RUNNING]");
    }

    @Test
    void transitionEventToStringIncludesNonEmptyPayloadAndFailure() {
        ActionBinding<StartAction> action = ActionBinding.of("test.start", ignored -> {
        });
        IllegalStateException failure = new IllegalStateException("boom");
        StateTransitionEvent event = new StateTransitionEvent(
                NEW,
                action.id(),
                new StartAction("request"),
                FAILED,
                failure);

        assertThat(event.toString()).isEqualTo(
                "StateTransitionEvent[from=NEW, action=test.start, payload=StartAction[request=request], "
                        + "to=FAILED, failure=java.lang.IllegalStateException: boom]");
    }

    @Test
    void describeIncludesCurrentStateAndTransitionDiagram() {
        ActionBinding<Void> start = ActionBinding.of("start", action -> {
        });
        ActionBinding<Void> stop = ActionBinding.of("stop", action -> {
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
    void describeIncludesChildMachines() {
        ActionBinding<Void> childStart = ActionBinding.of(ActionId.START, ignored -> {
        });
        StateMachine child = StateMachineDefinition.define()
                .from(NEW)
                .on(childStart)
                .to(RUNNING)
                .failTo(ERR)
                .build()
                .newStateMachine();
        ActionBinding<Void> propagateStart = propagatingAction(ActionId.START, false);
        StateMachine parent = StateMachineDefinition.define()
                .child("transport", child)
                .from(NEW)
                .on(propagateStart)
                .to(RUNNING)
                .failTo(ERR)
                .build()
                .newStateMachine();

        assertThat(parent.describe()).isEqualTo("""
                state: NEW
                in progress: <none>
                transitions:
                  NEW --START--> RUNNING (fail -> ERR)
                children:
                  transport:
                    state: NEW
                    in progress: <none>
                    transitions:
                      NEW --START--> RUNNING (fail -> ERR)""");

        parent.execute(ActionId.START, Void.EMPTY);

        assertThat(parent.describe()).isEqualTo("""
                state: RUNNING
                in progress: <none>
                transitions:
                  NEW --START--> RUNNING (fail -> ERR)
                children:
                  transport:
                    state: RUNNING
                    in progress: <none>
                    transitions:
                      NEW --START--> RUNNING (fail -> ERR)""");
        parent.close();
    }

    @Test
    void describeShowsTransitionInProgress() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        ActionBinding<Void> start = ActionBinding.of("slow-start", action -> {
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
            Future<StateTransitionEvent> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
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
        ActionBinding<Void> start = ActionBinding.of("start-service", action -> {
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
            Future<StateTransitionEvent> future = executor.submit(() -> machine.execute(start, Void.EMPTY));
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
            Future<StateTransitionEvent> startFuture =
                    executor.submit(() -> machine.execute(actions.start(), new StartAction("slow")));
            assertThat(firstHandlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<StateTransitionEvent> stopFuture =
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void executeRaw(
            StateMachine machine,
            StateTransition<?> transition,
            Object payload) {
        machine.execute(((StateTransition) transition).action(), payload);
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
        return ActionBinding.of(ActionId.START, ignored -> {
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
                .to(RUNNING)
                .failTo(ERR)
                .build()
                .newStateMachine();
    }

    private static ActionBinding<Void> propagatingAction(ActionId actionId, boolean parallel) {
        PropagatingAction action = new PropagatingAction(actionId, parallel);
        return action.binding();
    }

    private static final class PropagatingAction {
        private final ActionId actionId;
        private final boolean parallel;
        private final ActionBinding<Void> binding;

        private PropagatingAction(ActionId actionId, boolean parallel) {
            this.actionId = actionId;
            this.parallel = parallel;
            binding = actionId.bind(this::propagate);
        }

        private ActionBinding<Void> binding() {
            return binding;
        }

        private void propagate(Void payload) {
            if (parallel) {
                binding.stateMachine().propagateParallel(actionId, payload);
            } else {
                binding.stateMachine().propagateSequential(actionId, payload);
            }
        }
    }

    private static final class OrderedParentAction {
        private final List<String> calls;
        private final ActionBinding<Void> start = ActionId.START.bind(this::start);

        private OrderedParentAction(List<String> calls) {
            this.calls = calls;
        }

        private ActionBinding<Void> start() {
            return start;
        }

        private void start(Void payload) {
            calls.add("parent-before");
            start.stateMachine().propagateSequential(ActionId.START, payload);
            calls.add("parent-after");
        }
    }

    private static LogCapture captureStateMachineLogs() {
        return new LogCapture();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
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
                .to(RUNNING)
                .failTo(FAILED)

                .from(NEW, RUNNING)
                .on(actions.stop())
                .to(FIN)
                .failTo(FAILED)
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

        private TestActions(LifecycleActionHandler<StartAction> startHandler) {
            this(startHandler, action -> {
            });
        }

        private TestActions(
                LifecycleActionHandler<StartAction> startHandler,
                LifecycleActionHandler<StopAction> stopHandler) {
            start = ActionBinding.of("test.start", startHandler);
            stop = ActionBinding.of("test.stop", stopHandler);
        }

        private ActionBinding<StartAction> start() {
            return start;
        }

        private ActionBinding<StopAction> stop() {
            return stop;
        }
    }
}
