package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.config.schema.SshTransportConfig;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServer;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.*;

class TransportLifecycleStateMachineTest {
    @Test
    void aggregateConstructorTakesAllThreeChildStateMachines() {
        List<String> parameterTypes = new ArrayList<>();
        for (Constructor<?> constructor : TransportLifecycleStateMachine.class.getDeclaredConstructors()) {
            for (Type parameterType : constructor.getGenericParameterTypes()) {
                parameterTypes.add(parameterType.getTypeName());
            }
        }

        assertTrue(containsType(parameterTypes, GitNativeTransportStateMachine.class.getName()));
        assertTrue(containsType(parameterTypes, GitSshTransportStateMachine.class.getName()));
        assertTrue(containsType(parameterTypes, JettyHTTPServerStateMachine.class.getName()));
        assertFalse(containsType(parameterTypes, "GitNativeTransportService"));
        assertFalse(containsType(parameterTypes, "GitSshTransportService"));
        assertFalse(parameterTypes.contains(JettyHTTPServer.class.getName()));
    }

    @Test
    void transportAggregateIsStartedDirectlyByParentMachine() {
        OrionConfiguration configuration = configuration(true, true, true);
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService(configuration);
        TransportLifecycleStateMachine machine = machine(configuration, service);

        machine.start();
        assertEquals(RUNNING, machine.currentState());
        assertEquals(RUNNING, machine.gitNativeTransport().currentState());
        assertEquals(1, service.startCalls());
    }

    @Test
    void allChildrenAppearInStateMachineChildMap() throws Exception {
        OrionConfiguration configuration = configuration(true, true, true);
        TransportLifecycleStateMachine machine = machine(configuration, new RecordingGitNativeTransportService(configuration));

        machine.start();

        Map<String, ?> children = machine.aggregateStateMachine().childStatuses();
        assertTrue(children.containsKey("git-native"));
        assertTrue(children.containsKey("git-ssh"));
        assertTrue(children.containsKey("http"));
        assertEquals("""
                transports: RUNNING
                  git-native: RUNNING
                  git-ssh: DISABLED
                  http: DISABLED""", machine.aggregateStateMachine().describeStatus());
    }

    @Test
    void disabledTransportsMoveAggregateToDisabled() throws Exception {
        OrionConfiguration configuration = configuration(false, false, false);
        AtomicBoolean serviceResolved = new AtomicBoolean(false);
        GitNativeTransportStateMachine child = new GitNativeTransportStateMachine(() -> {
                    serviceResolved.set(true);
                    return new RecordingGitNativeTransportService(configuration);
        });
        TransportLifecycleStateMachine machine = machine(child,
                disabledSshMachine(), disabledHttpMachine());

        assertFalse(serviceResolved.get());
        assertEquals(NEW, machine.currentState());
        Map<String, ?> children = machine.aggregateStateMachine().childStatuses();
        assertTrue(children.containsKey("git-native"));
        assertTrue(children.containsKey("git-ssh"));
        assertTrue(children.containsKey("http"));
        machine.start();

        assertTrue(serviceResolved.get());
        assertEquals(DISABLED, machine.currentState());
        assertEquals("""
                transports: DISABLED
                  git-native: DISABLED
                  git-ssh: DISABLED
                  http: DISABLED""", machine.aggregateStateMachine().describeStatus());
    }

    @Test
    void anyEnabledTransportMovesAggregateToRunning() {
        // only git-native enabled
        OrionConfiguration configuration = configuration(true, false, false);
        TransportLifecycleStateMachine machine = machine(configuration, new RecordingGitNativeTransportService(configuration));

        machine.start();

        assertEquals(RUNNING, machine.currentState());
    }

    @Test
    void startFailureMovesAggregateToError() {
        OrionConfiguration configuration = configuration(true, false, false);
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService(configuration);
        RuntimeException failure = new RuntimeException("start failed");
        service.failStartWith(failure);
        TransportLifecycleStateMachine machine = machine(configuration, service);

        StateTransitionFailedException exception = assertThrows(StateTransitionFailedException.class, machine::start);

        assertSame(failure, rootCause(exception));
        assertEquals(ERR, machine.currentState());

        machine.stop();

        assertEquals(FIN, machine.currentState());
    }

    private static OrionConfiguration configuration(boolean gitEnabled, boolean sshEnabled, boolean httpEnabled) {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getTransport().getGit().setEnabled(gitEnabled);
        configuration.getTransport().getSsh().setEnabled(sshEnabled);
        configuration.getTransport().getHttp().setEnabled(httpEnabled);
        configuration.getTransport().getHttps().setEnabled(httpEnabled);
        return configuration;
    }

    private static TransportLifecycleStateMachine machine(
            OrionConfiguration configuration,
            RecordingGitNativeTransportService service) {
        GitNativeTransportStateMachine gitNative = new GitNativeTransportStateMachine(() -> service);
        return machine(gitNative, disabledSshMachine(), disabledHttpMachine());
    }

    private static TransportLifecycleStateMachine machine(
            GitNativeTransportStateMachine gitNative,
            GitSshTransportStateMachine gitSsh,
            JettyHTTPServerStateMachine jettyHttp) {
        return new TransportLifecycleStateMachine(gitNative, gitSsh, jettyHttp);
    }

    private static GitSshTransportStateMachine disabledSshMachine() {
        OrionConfiguration configuration = new OrionConfiguration();
        SshTransportConfig disabled = configuration.getTransport().getSsh();
        disabled.setEnabled(false);
        return new GitSshTransportStateMachine(() -> new GitSshTransportService(configuration, null, null, null));
    }

    private static JettyHTTPServerStateMachine disabledHttpMachine() {
        OrionConfiguration disabled = new OrionConfiguration();
        disabled.getTransport().getHttp().setEnabled(false);
        disabled.getTransport().getHttps().setEnabled(false);
        return new JettyHTTPServerStateMachine(() -> new JettyHTTPServer(disabled, null, null));
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
        private final boolean enabled;
        private boolean running;
        private int startCalls;
        private int stopCalls;
        private RuntimeException startFailure;

        private RecordingGitNativeTransportService(OrionConfiguration configuration) {
            super(configuration.getTransport().getGit(), null, null);
            enabled = configuration.getTransport().getGit().isEnabled();
        }

        @Override
        public void onStart() {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
            running = enabled;
        }

        @Override
        public void onStop() {
            stopCalls++;
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
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

}
