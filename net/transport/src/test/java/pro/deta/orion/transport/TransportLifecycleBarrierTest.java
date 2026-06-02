package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportLifecycleBarrierTest {
    @Test
    void registersTransportPhaseAnchorsWithoutStartingTransportEndpoints() throws Exception {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getTransport().getGit().setEnabled(true);
        TransportLifecycleBarrier barrier = new TransportLifecycleBarrier(configuration);
        RecordingRegistrar registrar = new RecordingRegistrar();

        barrier.registerToStage(registrar);

        LifecycleTaskDefinition start = registrar.definition(OrionLifecycleTasks.TRANSPORTS_START);
        assertEquals(ApplicationState.STARTING, start.phase());
        assertEquals("TransportLifecycleBarrier", start.serviceName());
        assertEquals(OrionLifecycleTasks.ACL_LOAD, start.after().getFirst());
        assertSame(OrionStageCallResult.EMPTY, start.call().call());

        LifecycleTaskDefinition stop = registrar.definition(OrionLifecycleTasks.TRANSPORTS_STOP);
        assertEquals(ApplicationState.STOPPING, stop.phase());
        assertEquals("TransportLifecycleBarrier", stop.serviceName());
        assertEquals(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_STOP, stop.after().getFirst());
        assertSame(OrionStageCallResult.EMPTY, stop.call().call());
    }

    @Test
    void sourceDocumentsTransitionalPhaseBridgeRule() throws Exception {
        String source = Files.readString(Path.of(
                "net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleBarrier.java"));

        assertTrue(source.contains("@AiRule Keep this class as a no-op phase bridge"));
        assertTrue(source.contains("does not start or stop endpoints"));
        assertTrue(source.contains("TransportLifecycleStateMachine"));
    }

    private static final class RecordingRegistrar implements ApplicationStateListenerRegistrar {
        private final Map<LifecycleTaskId, LifecycleTaskRegistration> registrations = new LinkedHashMap<>();

        @Override
        public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
            registrations.put(registration.definition().id(), registration);
            return registration;
        }

        private LifecycleTaskDefinition definition(LifecycleTaskId id) {
            return registrations.get(id).definition();
        }
    }
}
