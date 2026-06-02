package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.state.StateTransitionResult;
import pro.deta.orion.lifecycle.state.Void;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JettyHTTPServerStateMachineTest {
    @Test
    void startActionDoesNotExposeApplicationStageResult() {
        RecordingJettyHTTPServer server = new RecordingJettyHTTPServer();
        JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(() -> server);

        StateTransitionResult result = machine.stateMachine().execute(machine.startAction(), Void.EMPTY);

        assertSame(Void.EMPTY, result.actionResult());
        assertEquals(JettyHTTPServerStateMachine.RUNNING, machine.currentState());
        assertEquals(1, server.startCalls());
    }

    private static final class RecordingJettyHTTPServer extends JettyHTTPServer {
        private int startCalls;

        private RecordingJettyHTTPServer() {
            super(new OrionConfiguration(), null, null);
        }

        @Override
        public OrionStageCallResult onStart() {
            startCalls++;
            return new OrionStageCallResult(0);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        private int startCalls() {
            return startCalls;
        }
    }
}
