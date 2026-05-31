package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StateMachineDefinition.NEW;
import static pro.deta.orion.transport.git.GitNativeTransportStateMachine.RUNNING;

class TransportLifecycleStateMachineTest {
    @Test
    void aggregateConstructorUsesExplicitNativeGitStateMachineChild() {
        List<String> parameterTypes = new ArrayList<>();
        for (Constructor<?> constructor : TransportLifecycleStateMachine.class.getDeclaredConstructors()) {
            for (Type parameterType : constructor.getGenericParameterTypes()) {
                parameterTypes.add(parameterType.getTypeName());
            }
        }

        assertTrue(containsType(parameterTypes, GitNativeTransportStateMachine.class.getName()));
        assertFalse(containsType(parameterTypes, "GitNativeTransportService"));
    }

    @Test
    void registersNativeGitLifecycleTasksThroughTransportAggregate() throws Exception {
        OrionConfiguration configuration = configuration(true);
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService(configuration);
        GitNativeTransportStateMachine child = gitNativeTransport(configuration, service);
        TransportLifecycleStateMachine machine = new TransportLifecycleStateMachine(child);
        RecordingRegistrar registrar = new RecordingRegistrar();

        assertInstanceOf(OrionApplicationStageEventListener.class, machine);
        machine.registerToStage(registrar);

        LifecycleTaskDefinition start = registrar.definition(OrionLifecycleTasks.GIT_TRANSPORT_START);
        assertEquals(ApplicationState.STARTING, start.phase());
        assertEquals("TransportLifecycleStateMachine", start.serviceName());
        assertEquals(List.of(OrionLifecycleTasks.TRANSPORTS_START), start.after());

        LifecycleTaskDefinition stop = registrar.definition(OrionLifecycleTasks.GIT_TRANSPORT_STOP);
        assertEquals(ApplicationState.STOPPING, stop.phase());
        assertEquals("TransportLifecycleStateMachine", stop.serviceName());
        assertTrue(stop.after().isEmpty());

        assertNull(start.call().call());
        assertEquals(TransportLifecycleStateMachine.RUNNING, machine.currentState());
        assertEquals(RUNNING, child.currentState());
        assertEquals(Map.of("git-native", RUNNING), machine.stateMachine().childStates());
        assertEquals(1, service.startCalls());
        assertEquals("""
                transports: RUNNING
                  git-native: RUNNING""", machine.stateMachine().describeStatus());

        assertNull(stop.call().call());
        assertEquals(FIN, machine.currentState());
        assertEquals(FIN, child.currentState());
        assertEquals(Map.of("git-native", FIN), machine.stateMachine().childStates());
        assertEquals(1, service.stopCalls());
    }

    @Test
    void disabledNativeGitTransportDoesNotRegisterLifecycleTasksButKeepsChildMachine() {
        OrionConfiguration configuration = configuration(false);
        AtomicBoolean serviceResolved = new AtomicBoolean(false);
        GitNativeTransportStateMachine child = new GitNativeTransportStateMachine(configuration.getTransport().getGit(), () -> {
            serviceResolved.set(true);
            return new RecordingGitNativeTransportService(configuration);
        });
        TransportLifecycleStateMachine machine = new TransportLifecycleStateMachine(child);
        RecordingRegistrar registrar = new RecordingRegistrar();

        machine.registerToStage(registrar);

        assertTrue(registrar.registrations.isEmpty());
        assertFalse(serviceResolved.get());
        assertEquals(NEW, machine.currentState());
        assertFalse(machine.gitNativeTransport().isEnabled());
        assertEquals(Map.of("git-native", NEW), machine.stateMachine().childStates());
        assertEquals("""
                transports: DISABLED (state=NEW)
                  git-native: DISABLED (state=NEW)""", machine.stateMachine().describeStatus());
    }

    @Test
    void startFailureMovesAggregateToErrorAndStopFinishesChild() {
        OrionConfiguration configuration = configuration(true);
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService(configuration);
        RuntimeException failure = new RuntimeException("start failed");
        service.failStartWith(failure);
        GitNativeTransportStateMachine child = gitNativeTransport(configuration, service);
        TransportLifecycleStateMachine machine = new TransportLifecycleStateMachine(child);

        StateTransitionFailedException exception = assertThrows(StateTransitionFailedException.class, machine::start);

        assertSame(failure, rootCause(exception));
        assertEquals(ERR, machine.currentState());
        assertEquals(ERR, child.currentState());
        assertEquals(1, service.startCalls());

        machine.stop();

        assertEquals(FIN, machine.currentState());
        assertEquals(FIN, child.currentState());
        assertEquals(1, service.stopCalls());
    }

    private static OrionConfiguration configuration(boolean gitEnabled) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getTransport().getGit().setEnabled(gitEnabled);
        return configuration;
    }

    private static GitNativeTransportStateMachine gitNativeTransport(
            OrionConfiguration configuration,
            RecordingGitNativeTransportService service) {
        return new GitNativeTransportStateMachine(configuration.getTransport().getGit(), () -> service);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static boolean containsType(List<String> parameterTypes, String value) {
        for (String parameterType : parameterTypes) {
            if (parameterType.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static final class RecordingGitNativeTransportService extends GitNativeTransportService {
        private int startCalls;
        private int stopCalls;
        private RuntimeException startFailure;

        private RecordingGitNativeTransportService(OrionConfiguration configuration) {
            super(configuration.getTransport().getGit(), null, null);
        }

        @Override
        public pro.deta.orion.lifecycle.data.OrionStageCallResult onStart() {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
            return null;
        }

        @Override
        public pro.deta.orion.lifecycle.data.OrionStageCallResult onStop() {
            stopCalls++;
            return null;
        }

        private void failStartWith(RuntimeException failure) {
            startFailure = failure;
        }

        private int startCalls() {
            return startCalls;
        }

        private int stopCalls() {
            return stopCalls;
        }
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
