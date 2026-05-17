package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.lifecycle.state.InvalidStateTransitionException;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.lifecycle.state.Void;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.*;
import static pro.deta.orion.transport.git.GitNativeTransportStateMachine.RUNNING;

class GitNativeTransportStateMachineTest {
    @Test
    void startThenStopReachesFinishedState() {
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService();
        GitNativeTransportStateMachine machine = new GitNativeTransportStateMachine(service);

        assertEquals(NEW, machine.currentState());
        assertSame(service, machine.service());
        assertEquals(Set.of(machine.startAction(), machine.stopAction()), machine.stateMachine().availableActions());

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
    void definitionUsesTheSameActionBindingInstances() {
        GitNativeTransportStateMachine machine =
                new GitNativeTransportStateMachine(new RecordingGitNativeTransportService());

        assertEquals(Set.of(machine.startAction(), machine.stopAction()), machine.definition().availableActions(NEW));
        assertEquals(Set.of(machine.stopAction()), machine.definition().availableActions(RUNNING));
        assertEquals(Set.of(machine.stopAction()), machine.definition().availableActions(ERR));
        assertTrue(machine.definition().availableActions(FIN).isEmpty());
        assertEquals(NEW, machine.definition().newStateMachine().currentState());
    }

    @Test
    void describeIncludesCurrentStateAndNativeTransportDiagram() {
        GitNativeTransportStateMachine machine =
                new GitNativeTransportStateMachine(new RecordingGitNativeTransportService());

        assertTrue(machine.describe().contains("state: NEW"));
        assertTrue(machine.describe().contains("in progress: <none>"));
        assertTrue(machine.describe().contains("NEW --git-native-transport.start--> RUNNING (fail -> ERR)"));
        assertTrue(machine.describe().contains("RUNNING --git-native-transport.stop--> FIN (fail -> ERR)"));
    }
}
