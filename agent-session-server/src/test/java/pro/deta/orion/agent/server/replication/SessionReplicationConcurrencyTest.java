package pro.deta.orion.agent.server.replication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.journal.FileSystemSessionJournalStorage;
import pro.deta.orion.agent.server.journal.JournalStorageConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionReplicationConcurrencyTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final SessionEventCodec EVENTS = new SessionEventCodec(LIMITS);
    private static final AgentId AGENT_ID = new AgentId("agent-1");
    private static final SessionId SESSION_ID = new SessionId("session-1");

    @TempDir
    Path root;

    @Test
    void overlappingPhysicalStreamsConvergeThroughDurableStorage() throws Exception {
        List<SessionEventRecord> records = events(1, 10, (byte) 0);
        try (var storage = storage();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SessionReplicationService service = service(storage);

            var shorter = executor.submit(
                    () -> service.append(SESSION_ID, records.subList(0, 5)));
            var longer = executor.submit(
                    () -> service.append(SESSION_ID, records));

            assertThat(shorter.get(10, TimeUnit.SECONDS).afterEventId())
                    .hasValueSatisfying(cursor -> assertThat(cursor)
                            .isIn(new EventId(5), new EventId(10)));
            assertThat(longer.get(10, TimeUnit.SECONDS).afterEventId())
                    .contains(new EventId(10));
            assertThat(storage.readAfter(SESSION_ID, Optional.empty()).records())
                    .containsExactlyElementsOf(records);
        }
    }

    @Test
    void shorterRetryUsesAnAlreadyAdvancedDurableCursor() throws Exception {
        List<SessionEventRecord> records = events(1, 10, (byte) 0);
        try (var storage = storage()) {
            SessionReplicationService service = service(storage);
            service.append(SESSION_ID, records);

            AgentMessage.SessionSync acknowledgement =
                    service.append(SESSION_ID, records.subList(0, 5));

            assertThat(acknowledgement.afterEventId()).contains(new EventId(10));
            assertThat(storage.readAfter(SESSION_ID, Optional.empty()).records())
                    .containsExactlyElementsOf(records);
        }
    }

    @Test
    void conflictingPhysicalStreamCannotChangeDurableHistory() throws Exception {
        SessionEventRecord original = event(1, (byte) 1);
        SessionEventRecord conflict = event(1, (byte) 2);
        try (var storage = storage()) {
            SessionReplicationService service = service(storage);
            service.append(SESSION_ID, List.of(original));

            assertThatThrownBy(() -> service.append(SESSION_ID, List.of(conflict)))
                    .isInstanceOfSatisfying(SessionReplicationException.class,
                            failure -> assertThat(failure.kind())
                                    .isEqualTo(SessionReplicationException.Kind.PROTOCOL));
            assertThat(storage.readAfter(SESSION_ID, Optional.empty()).records())
                    .containsExactly(original);
        }
    }

    @Test
    void serverRestartResumesFromDurableJournalAlone() throws Exception {
        SessionEventRecord event = event(1, (byte) 1);
        try (var storage = storage()) {
            service(storage).append(SESSION_ID, List.of(event));
        }

        try (var reopened = storage()) {
            AgentMessage.SessionSync synchronization = service(reopened).open(
                    AGENT_ID,
                    new AgentMessage.SessionOpen(
                            SESSION_ID,
                            Optional.of(event.eventId()),
                            Optional.of(event.eventId()),
                            AgentMessage.SessionState.RUNNING));

            assertThat(synchronization.afterEventId()).contains(event.eventId());
        }
    }

    private FileSystemSessionJournalStorage storage() {
        return new FileSystemSessionJournalStorage(root, new JournalStorageConfig(LIMITS));
    }

    private static SessionReplicationService service(FileSystemSessionJournalStorage storage) {
        return new SessionReplicationService(storage);
    }

    private static List<SessionEventRecord> events(long first, long last, byte payloadOffset)
            throws AgentProtocolException {
        List<SessionEventRecord> records = new ArrayList<>();
        for (long eventId = first; eventId <= last; eventId++) {
            records.add(event(eventId, (byte) (eventId + payloadOffset)));
        }
        return List.copyOf(records);
    }

    private static SessionEventRecord event(long eventId, byte payload)
            throws AgentProtocolException {
        return EVENTS.decode(EVENTS.encode(
                new EventId(eventId),
                new SessionEventPayload.PtyOutput(
                        ProtocolBytes.copyOf(new byte[]{payload}))));
    }
}
