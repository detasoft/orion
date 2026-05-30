package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.OrionLifecycleStateMachine;
import pro.deta.orion.lifecycle.state.InvalidStateTransitionException;
import pro.deta.orion.lifecycle.state.StateMachineEvent;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.lifecycle.state.Void;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void nativeGitStateMachineIsOnlyAStateMachineMarker() {
        GitNativeTransportStateMachine machine =
                machine(new RecordingGitNativeTransportService());

        assertInstanceOf(OrionLifecycleStateMachine.class, machine);
        assertFalse(OrionApplicationStageEventListener.class.isAssignableFrom(GitNativeTransportStateMachine.class));
    }

    @Test
    void nativeGitStateMachineIsDaggerManagedButNotAnApplicationListener() {
        assertTrue(GitNativeTransportStateMachine.class.isAnnotationPresent(Singleton.class));
        assertEquals(GitTransportConfig.class, injectConstructor().getParameterTypes()[0]);
    }

    @Test
    void startThenStopReachesFinishedState() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = machine(service);

        assertEquals(NEW, machine.currentState());
        assertTrue(machine.isEnabled());
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
        GitNativeTransportStateMachine machine = machine(service);

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
        GitNativeTransportStateMachine machine = machine(service);

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
                machine(new RecordingGitNativeTransportService());

        machine.stop();

        assertThrows(InvalidStateTransitionException.class, machine::start);
        assertThrows(InvalidStateTransitionException.class, machine::stop);
    }

    @Test
    void exposedActionBindingCanBeExecutedDirectly() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = machine(service);

        machine.stateMachine().execute(machine.startAction(), Void.EMPTY);

        assertEquals(RUNNING, machine.currentState());
        assertEquals(1, service.startCalls());
    }

    @Test
    void definitionReportsAvailableActionsAndRejectsSecondMachine() {
        GitNativeTransportStateMachine machine =
                machine(new RecordingGitNativeTransportService());

        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.definition().availableActions(NEW));
        assertEquals(Set.of(machine.stopAction().id()), machine.definition().availableActions(RUNNING));
        assertEquals(Set.of(machine.stopAction().id()), machine.definition().availableActions(ERR));
        assertTrue(machine.definition().availableActions(FIN).isEmpty());
        assertThrows(IllegalStateException.class, () -> machine.definition().newStateMachine());
    }

    @Test
    void describeIncludesCurrentStateAndNativeTransportDiagram() {
        GitNativeTransportStateMachine machine =
                machine(new RecordingGitNativeTransportService());

        assertTrue(machine.describe().contains("state: NEW"));
        assertTrue(machine.describe().contains("in progress: <none>"));
        assertTrue(machine.describe().contains("NEW --START--> RUNNING (fail -> ERR)"));
        assertTrue(machine.describe().contains("RUNNING --STOP--> FIN (fail -> ERR)"));
    }

    @Test
    void subscriptionCanBeAttachedToWrapper() {
        GitNativeTransportStateMachine machine =
                machine(new RecordingGitNativeTransportService());
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

    @Test
    void disabledMachineKeepsStateMachineButDoesNotResolveServiceProvider() {
        AtomicBoolean serviceResolved = new AtomicBoolean(false);
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(config(false), () -> {
            serviceResolved.set(true);
            return new RecordingGitNativeTransportService(false);
        });

        assertFalse(machine.isEnabled());
        assertEquals(NEW, machine.currentState());

        machine.start();
        machine.stop();

        assertFalse(serviceResolved.get());
        assertEquals(FIN, machine.currentState());
    }

    private static GitNativeTransportStateMachine machine(RecordingGitNativeTransportService service) {
        return new GitNativeTransportStateMachine(config(true), () -> service);
    }

    private static Constructor<?> injectConstructor() {
        for (Constructor<?> constructor : GitNativeTransportStateMachine.class.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                return constructor;
            }
        }
        throw new AssertionError("Missing @Inject constructor");
    }

    private static GitTransportConfig config(boolean enabled) {
        GitTransportConfig config = new GitTransportConfig("127.0.0.1", 0);
        config.setEnabled(enabled);
        return config;
    }

}
