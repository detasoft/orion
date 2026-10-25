package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import pro.deta.orion.test.integration.TestConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JettyHTTPServerStateMachineTest {
    private static final pro.deta.orion.test.util.TestEvents EVENTS =
            new pro.deta.orion.test.util.TestEvents();

    @org.junit.jupiter.api.AfterEach
    void stopTestEvents() {
        EVENTS.close();
    }

    @Test
    void stateMachineDefinitionComesFromGenericServiceAdapter() {
        TestConfiguration desiredState = new TestConfiguration(EVENTS);
        desiredState.publish(new OrionDocument(
                new OrionDocument.SystemConfiguration(new AccessControl(), Optional.empty(), List.of(), List.of(), List.of(),
                List.of(), List.of()),
                List.of()), Optional.of("test-revision"));
        JettyHTTPServer server =
                new JettyHTTPServer(
                        new BootstrapConfiguration(),
                        desiredState.editor(),
                        OrionKeyMaterial.unavailable(),
                        null,
                        null,
                        null);
        JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(
                () -> server, () -> { throw new AssertionError(); }, () -> { throw new AssertionError(); },
                EVENTS.rawManager());

        assertEquals("http", machine.stateMachine().name());
        assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.stateMachine().availableActions());
    }
}
