package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JettyHTTPServerStateMachineTest {
    @Test
    void stateMachineDefinitionComesFromGenericServiceAdapter() {
        JettyHTTPServer server = new JettyHTTPServer(new OrionConfiguration(), null, null);
        JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(() -> server);

        assertEquals("http", machine.stateMachine().name());
        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.stateMachine().availableActions());
    }
}
