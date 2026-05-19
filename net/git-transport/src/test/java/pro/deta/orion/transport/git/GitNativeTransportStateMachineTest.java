package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionLifecycleStateMachine;
import pro.deta.orion.lifecycle.state.InvalidStateTransitionException;
import pro.deta.orion.lifecycle.state.StateMachineEvent;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.lifecycle.state.Void;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.*;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.AFTER_STATE_ENTERED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_STARTED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_STARTED;
import static pro.deta.orion.transport.git.GitNativeTransportStateMachine.RUNNING;

class GitNativeTransportStateMachineTest {
    @Test
    void registersNativeGitLifecycleTasksAsStateMachineService() throws Exception {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(service);
        RecordingRegistrar registrar = new RecordingRegistrar();

        assertInstanceOf(OrionLifecycleStateMachine.class, machine);
        machine.registerToStage(registrar);

        LifecycleTaskDefinition start = registrar.definition(OrionLifecycleTasks.GIT_TRANSPORT_START);
        assertEquals(ApplicationState.STARTING, start.phase());
        assertEquals("GitNativeTransportStateMachine", start.serviceName());
        assertEquals(List.of(OrionLifecycleTasks.TRANSPORTS_START), start.after());

        LifecycleTaskDefinition stop = registrar.definition(OrionLifecycleTasks.GIT_TRANSPORT_STOP);
        assertEquals(ApplicationState.STOPPING, stop.phase());
        assertEquals("GitNativeTransportStateMachine", stop.serviceName());
        assertTrue(stop.after().isEmpty());

        assertNull(start.call().call());
        assertEquals(RUNNING, machine.currentState());
        assertEquals(1, service.startCalls());

        assertNull(stop.call().call());
        assertEquals(FIN, machine.currentState());
        assertEquals(1, service.stopCalls());
    }

    @Test
    void disabledNativeGitTransportDoesNotRegisterLifecycleTasks() {
        GitNativeTransportStateMachine machine =
                new GitNativeTransportStateMachine(new RecordingGitNativeTransportService(false));
        RecordingRegistrar registrar = new RecordingRegistrar();

        machine.registerToStage(registrar);

        assertTrue(registrar.registrations.isEmpty());
    }

    @Test
    void startThenStopReachesFinishedState() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(service);

        assertEquals(NEW, machine.currentState());
        assertSame(service, machine.service());
        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.stateMachine().availableActions());

        machine.start();
        assertEquals(RUNNING, machine.currentState());
        assertEquals(1, service.startCalls());
        assertEquals(0, service.stopCalls());

        machine.stop();
        assertEquals(FIN, machine.currentState());
        assertTrue(machine.snapshot().terminal());
        assertTrue(machine.stateMachine().availableActions().isEmpty());
        assertEquals(1, service.stopCalls());
    }

    @Test
    void stopBeforeStartReachesFinishedStateWithoutStartingService() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(service);

        machine.stop();

        assertEquals(FIN, machine.currentState());
        assertEquals(0, service.startCalls());
        assertEquals(1, service.stopCalls());
    }

    @Test
    void startFailureMovesToErrorAndCanStillBeStopped() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        RuntimeException failure = new RuntimeException("start failed");
        service.failStartWith(failure);
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(service);

        StateTransitionFailedException exception = assertThrows(StateTransitionFailedException.class, machine::start);
        assertSame(failure, exception.getCause());
        assertEquals(ERR, machine.currentState());

        machine.stop();

        assertEquals(FIN, machine.currentState());
        assertEquals(1, service.startCalls());
        assertEquals(1, service.stopCalls());
    }

    @Test
    void finishedStateRejectsFurtherActions() {
        GitNativeTransportStateMachine machine =
                new GitNativeTransportStateMachine(new RecordingGitNativeTransportService());

        machine.stop();

        assertThrows(InvalidStateTransitionException.class, machine::start);
        assertThrows(InvalidStateTransitionException.class, machine::stop);
    }

    @Test
    void exposedActionBindingCanBeExecutedDirectly() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(service);

        machine.stateMachine().execute(machine.startAction(), Void.EMPTY);

        assertEquals(RUNNING, machine.currentState());
        assertEquals(1, service.startCalls());
    }

    @Test
    void definitionReportsAvailableActionsAndRejectsSecondMachine() {
        GitNativeTransportStateMachine machine =
                new GitNativeTransportStateMachine(new RecordingGitNativeTransportService());

        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.definition().availableActions(NEW));
        assertEquals(Set.of(machine.stopAction().id()), machine.definition().availableActions(RUNNING));
        assertEquals(Set.of(machine.stopAction().id()), machine.definition().availableActions(ERR));
        assertTrue(machine.definition().availableActions(FIN).isEmpty());
        assertThrows(IllegalStateException.class, () -> machine.definition().newStateMachine());
    }

    @Test
    void describeIncludesCurrentStateAndNativeTransportDiagram() {
        GitNativeTransportStateMachine machine =
                new GitNativeTransportStateMachine(new RecordingGitNativeTransportService());

        assertTrue(machine.describe().contains("state: NEW"));
        assertTrue(machine.describe().contains("in progress: <none>"));
        assertTrue(machine.describe().contains("NEW --START--> RUNNING (fail -> ERR)"));
        assertTrue(machine.describe().contains("RUNNING --STOP--> FIN (fail -> ERR)"));
    }

    @Test
    void subscriptionCanBeAttachedToWrapper() {
        GitNativeTransportStateMachine machine =
                new GitNativeTransportStateMachine(new RecordingGitNativeTransportService());
        List<StateMachineEvent> events = new ArrayList<>();

        machine.subscribe(events::add);
        machine.start();

        assertEquals(
                List.of(
                        TRANSITION_STARTED,
                        TRANSITION_FUNCTION_STARTED,
                        TRANSITION_FUNCTION_FINISHED,
                        AFTER_STATE_ENTERED,
                        TRANSITION_FINISHED),
                events.stream().map(StateMachineEvent::type).toList());
        assertEquals(RUNNING, events.get(3).currentState());
    }

    private static final class RecordingRegistrar implements ApplicationStateListenerRegistrar {
        private final List<LifecycleTaskRegistration> registrations = new ArrayList<>();

        @Override
        public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
            registrations.add(registration);
            return registration;
        }

        private LifecycleTaskDefinition definition(LifecycleTaskId id) {
            for (LifecycleTaskRegistration registration : registrations) {
                LifecycleTaskDefinition definition = registration.definition();
                if (definition.id().equals(id)) {
                    return definition;
                }
            }
            throw new AssertionError("Missing lifecycle task " + id);
        }
    }
}
