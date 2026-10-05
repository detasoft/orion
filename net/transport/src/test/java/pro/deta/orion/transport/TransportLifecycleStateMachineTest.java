package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.bootstrap.config.SshTransportConfig;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.lifecycle.state.StateTransitionFailedException;
import pro.deta.orion.transport.git.DefaultGitNativeRepositoryService;
import pro.deta.orion.transport.git.GitNativeTransportService;
import pro.deta.orion.transport.git.GitNativeTransportStateMachine;
import pro.deta.orion.transport.git.GitSshTransportService;
import pro.deta.orion.transport.git.GitSshTransportStateMachine;
import pro.deta.orion.transport.http.JettyHTTPServer;
import pro.deta.orion.transport.http.JettyHTTPServerStateMachine;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.*;

class TransportLifecycleStateMachineTest {
    @Test
    void transportAggregateIsStartedDirectlyByParentMachine() {
        BootstrapConfiguration configuration = configuration(true, true, true);
        RecordingGitNativeTransportService service = new RecordingGitNativeTransportService(configuration);
        TransportLifecycleStateMachine machine = machine(configuration, service);

        machine.start();
        assertEquals(RUNNING, machine.currentState());
        assertEquals(RUNNING, machine.gitNativeTransport().currentState());
        assertEquals(1, service.startCalls());
    }

    @Test
    void allChildrenAppearInStateMachineChildMap() throws Exception {
        BootstrapConfiguration configuration = configuration(true, true, true);
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
        BootstrapConfiguration configuration = configuration(false, false, false);
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
        BootstrapConfiguration configuration = configuration(true, false, false);
        TransportLifecycleStateMachine machine = machine(configuration, new RecordingGitNativeTransportService(configuration));

        machine.start();

        assertEquals(RUNNING, machine.currentState());
    }

    @Test
    void startFailureMovesAggregateToError() {
        BootstrapConfiguration configuration = configuration(true, false, false);
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

    private static BootstrapConfiguration configuration(boolean gitEnabled, boolean sshEnabled, boolean httpEnabled) {
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        configuration.getTransport().getGit().setEnabled(gitEnabled);
        configuration.getTransport().getSsh().setEnabled(sshEnabled);
        configuration.getTransport().getHttp().setEnabled(httpEnabled);
        return configuration;
    }

    private static TransportLifecycleStateMachine machine(
            BootstrapConfiguration configuration,
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
        BootstrapConfiguration configuration = new BootstrapConfiguration();
        SshTransportConfig disabled = configuration.getTransport().getSsh();
        disabled.setEnabled(false);
        return new GitSshTransportStateMachine(() -> new GitSshTransportService(
                configuration,
                null,
                null,
                null,
                null));
    }

    private static JettyHTTPServerStateMachine disabledHttpMachine() {
        BootstrapConfiguration disabled = new BootstrapConfiguration();
        disabled.getTransport().getHttp().setEnabled(false);
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(), List.of(), List.of(), List.of(),
                List.of()),
                List.of()), Optional.of("test-revision"));
        return new JettyHTTPServerStateMachine(() -> new JettyHTTPServer(
                disabled, desiredState, TlsCapability.unavailable(), null, null, null),
                () -> { throw new AssertionError("Disabled HTTP must not start ACME maintenance"); },
                () -> { throw new AssertionError("Disabled HTTP must not start Git cleanup"); });
    }

    private static Throwable rootCause(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static final class RecordingGitNativeTransportService extends GitNativeTransportService {
        private final boolean enabled;
        private boolean running;
        private int startCalls;
        private int stopCalls;
        private RuntimeException startFailure;

        private RecordingGitNativeTransportService(BootstrapConfiguration configuration) {
            super(
                    configuration.getTransport().getGit(),
                    new DefaultGitNativeRepositoryService(
                            NativeGitRepositoryProvider.inMemory()));
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
        public boolean isEnabled() {
            return enabled;
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
