package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.TlsCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JettyHTTPServerStateMachineTest {
    @Test
    void stateMachineDefinitionComesFromGenericServiceAdapter() {
        OrionDesiredState desiredState = new OrionDesiredState();
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty()),
                List.of()), Optional.of("test-revision"));
        JettyHTTPServer server = new JettyHTTPServer(
                new OrionConfiguration(), desiredState, TlsCapability.unavailable(), null, null);
        JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(() -> server);

        assertEquals("http", machine.stateMachine().name());
        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.stateMachine().availableActions());
    }
}
