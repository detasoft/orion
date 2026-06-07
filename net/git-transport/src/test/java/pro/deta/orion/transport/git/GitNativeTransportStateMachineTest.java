package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.state.*;
import pro.deta.orion.lifecycle.state.Void;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.AFTER_STATE_ENTERED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_STARTED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_FUNCTION_FINISHED;
import static pro.deta.orion.lifecycle.state.StateMachineEventType.TRANSITION_STARTED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.*;

class GitNativeTransportStateMachineTest {
    @Test
    void nativeGitStateMachineIsNotAnApplicationListener() {
        assertFalse(OrionApplicationStageEventListener.class.isAssignableFrom(GitNativeTransportStateMachine.class));
    }

    @Test
    void nativeGitStateMachineUsesGenericServiceLifecycleAdapter() {
        assertTrue(ServiceLifecycleStateMachineAdapter.class.isAssignableFrom(GitNativeTransportStateMachine.class));
    }

    @Test
    void nativeGitStateMachineIsDaggerManagedButNotAnApplicationListener() {
        assertTrue(GitNativeTransportStateMachine.class.isAnnotationPresent(Singleton.class));
        assertEquals(1, injectConstructor().getParameterCount());
    }

    @Test
    void startThenStopReachesFinishedState() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = machine(service);

        assertEquals(NEW, machine.currentState());
        assertTrue(service.isEnabled());
        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.stateMachine().availableActions());

        machine.start();
        assertEquals(RUNNING, machine.currentState());
        assertEquals(1, service.startCalls());
        assertEquals(0, service.stopCalls());

        machine.stop();
        assertEquals(FIN, machine.currentState());
        assertTrue(machine.stateMachine().status().terminal());
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
        assertEquals(0, service.stopCalls());
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
        assertEquals(
                Set.of(machine.startAction().id(), machine.stopAction().id()),
                machine.definition().availableActions(StandardStateDefinition.DISABLED));
        assertEquals(Set.of(machine.stopAction().id()), machine.definition().availableActions(RUNNING));
        assertEquals(Set.of(machine.stopAction().id()), machine.definition().availableActions(ERR));
        assertTrue(machine.definition().availableActions(FIN).isEmpty());
        assertThrows(IllegalStateException.class, () -> machine.definition().newStateMachine());
    }

    @Test
    void describeIncludesCurrentStateAndNativeTransportDiagram() {
        GitNativeTransportStateMachine machine =
                machine(new RecordingGitNativeTransportService());

        assertTrue(machine.stateMachine().describe().contains("state: NEW"));
        assertTrue(machine.stateMachine().describe().contains("in progress: <none>"));
        assertTrue(machine.stateMachine().describe().contains("NEW --START--> [DISABLED, RUNNING, ERR]"));
        assertTrue(machine.stateMachine().describe().contains("DISABLED --START--> [DISABLED, RUNNING, ERR]"));
        assertTrue(machine.stateMachine().describe().contains("RUNNING --STOP--> FIN (fail -> ERR)"));
    }

    @Test
    void subscriptionCanBeAttachedToWrapper() {
        GitNativeTransportStateMachine machine =
                machine(new RecordingGitNativeTransportService());
        List<StateMachineEvent> events = new ArrayList<>();

        machine.stateMachine().subscribe(events::add);
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
    void disabledMachineMovesToDisabledAfterServiceReportsDisabled() {
        AtomicBoolean serviceResolved = new AtomicBoolean(false);
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(() -> {
            serviceResolved.set(true);
            return new RecordingGitNativeTransportService(false);
        });

        assertEquals(NEW, machine.currentState());

        machine.start();
        assertEquals(DISABLED, machine.currentState());
        machine.stop();

        assertTrue(serviceResolved.get());
        assertEquals(FIN, machine.currentState());
    }

    @Test
    void doubleStopThrowsAfterTerminalState() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = machine(service);

        machine.start();
        machine.stop();

        assertThrows(InvalidStateTransitionException.class, machine::stop);
        assertEquals(FIN, machine.currentState());
        assertEquals(1, service.stopCalls());
    }

    @Test
    void startAfterFailedStartThrowsInvalidStateTransition() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        service.failStartWith(new RuntimeException("start failed"));
        GitNativeTransportStateMachine machine = machine(service);

        assertThrows(StateTransitionFailedException.class, machine::start);
        assertEquals(ERR, machine.currentState());
        assertEquals(1, service.startCalls());

        assertThrows(InvalidStateTransitionException.class, machine::start);
        assertEquals(ERR, machine.currentState());
        assertEquals(1, service.startCalls());
    }

    @Test
    void concurrentStartAndStopSerializeTransitions() throws InterruptedException {
        CountDownLatch startEntering = new CountDownLatch(1);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch stopQueued = new CountDownLatch(1);
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        service.blockStartWith(startEntering, startGate);
        GitNativeTransportStateMachine machine = machine(service);

        AtomicReference<Throwable> startError = new AtomicReference<>();
        AtomicReference<Throwable> stopError = new AtomicReference<>();

        Thread startThread = new Thread(() -> {
            try { machine.start(); } catch (Throwable t) { startError.set(t); }
        });
        Thread stopThread = new Thread(() -> {
            stopQueued.countDown();
            try { machine.stop(); } catch (Throwable t) { stopError.set(t); }
        });

        startThread.start();
        assertTrue(startEntering.await(5, TimeUnit.SECONDS), "start did not enter service within timeout");

        // start holds the state-machine lock inside onStart(); stop will block until start releases it
        stopThread.start();
        assertTrue(stopQueued.await(5, TimeUnit.SECONDS), "stop thread did not queue within timeout");

        startGate.countDown();
        assertTrue(startThread.join(Duration.ofSeconds(5)));
        assertTrue(stopThread.join(Duration.ofSeconds(5)));

        assertNull(startError.get(), "start threw unexpectedly");
        assertNull(stopError.get(), "stop threw unexpectedly");
        assertEquals(FIN, machine.currentState());
        assertEquals(1, service.startCalls());
        assertEquals(1, service.stopCalls());
    }

    private static GitNativeTransportStateMachine machine(RecordingGitNativeTransportService service) {
        return new GitNativeTransportStateMachine(() -> service);
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
