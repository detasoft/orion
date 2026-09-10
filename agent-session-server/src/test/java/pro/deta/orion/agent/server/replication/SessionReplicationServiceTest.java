package pro.deta.orion.agent.server.replication;

import org.junit.jupiter.api.Test;
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
import pro.deta.orion.agent.server.journal.JournalAppendResult;
import pro.deta.orion.agent.server.journal.JournalGap;
import pro.deta.orion.agent.server.journal.JournalReadResult;
import pro.deta.orion.agent.server.journal.JournalStorageException;
import pro.deta.orion.agent.server.journal.SessionJournalStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionReplicationServiceTest {
    private static final SessionEventCodec EVENTS =
            new SessionEventCodec(AgentProtocolLimits.defaults());
    private static final AgentId AGENT_ID = new AgentId("agent-1");
    private static final SessionId SESSION_ID = new SessionId("session-1");

    @Test
    void derivesTheResumeCursorFromDurableStorage() throws Exception {
        RecordingStorage storage = new RecordingStorage(Optional.of(new EventId(12)));
        List<JournalGap> gaps = new ArrayList<>();
        SessionReplicationService service = new SessionReplicationService(
                storage, (agentId, sessionId, gap) -> gaps.add(gap));

        AgentMessage.SessionSync result = service.open(
                AGENT_ID, open(Optional.of(new EventId(1)), Optional.of(new EventId(20))));

        assertThat(result).isEqualTo(
                new AgentMessage.SessionSync(SESSION_ID, Optional.of(new EventId(12))));
        assertThat(storage.lastRequestedFor).isEqualTo(SESSION_ID);
        assertThat(gaps).isEmpty();
    }

    @Test
    void recordsUnavailableHistoryBeforeReturningTheDurableCursor() throws Exception {
        RecordingStorage storage = new RecordingStorage(Optional.of(new EventId(5)));
        List<JournalGap> gaps = new ArrayList<>();
        SessionReplicationService service = new SessionReplicationService(
                storage,
                (agentId, sessionId, gap) -> {
                    assertThat(agentId).isEqualTo(AGENT_ID);
                    assertThat(sessionId).isEqualTo(SESSION_ID);
                    gaps.add(gap);
                });

        AgentMessage.SessionSync result = service.open(
                AGENT_ID, open(Optional.of(new EventId(10)), Optional.of(new EventId(20))));

        assertThat(gaps).containsExactly(new JournalGap(new EventId(5), new EventId(10)));
        assertThat(result.afterEventId()).contains(new EventId(5));
    }

    @Test
    void requestsTheFirstAvailableEventForAnEmptyServerJournal() throws Exception {
        RecordingStorage storage = new RecordingStorage(Optional.empty());
        List<JournalGap> gaps = new ArrayList<>();
        SessionReplicationService service = new SessionReplicationService(
                storage, (agentId, sessionId, gap) -> gaps.add(gap));

        AgentMessage.SessionSync result = service.open(
                AGENT_ID, open(Optional.empty(), Optional.empty()));

        assertThat(result.afterEventId()).isEmpty();
        assertThat(gaps).isEmpty();
    }

    @Test
    void gapRecordingFailurePreventsSynchronization() {
        RecordingStorage storage = new RecordingStorage(Optional.of(new EventId(5)));
        GapRecordingException recordingFailure = new GapRecordingException(
                "metadata unavailable", new IllegalStateException("unavailable"));
        SessionReplicationService service = new SessionReplicationService(
                storage,
                (agentId, sessionId, gap) -> {
                    throw recordingFailure;
                });

        assertThatThrownBy(() -> service.open(
                AGENT_ID, open(Optional.of(new EventId(10)), Optional.of(new EventId(20)))))
                .isInstanceOfSatisfying(SessionReplicationException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(SessionReplicationException.Kind.INTERNAL))
                .hasCause(recordingFailure);
    }

    @Test
    void acknowledgesOnlyAfterStorageAppendCompletes() throws Exception {
        RecordingStorage storage = new RecordingStorage(Optional.empty());
        SessionEventRecord event = event(1, (byte) 1);
        storage.appendResult = new JournalAppendResult(
                Optional.of(event.eventId()), List.of(event));
        storage.blockAppend = true;
        SessionReplicationService service = new SessionReplicationService(
                storage, (agentId, sessionId, gap) -> { });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AgentMessage.SessionSync> acknowledgement = executor.submit(
                    () -> service.append(SESSION_ID, List.of(event)));
            assertThat(storage.appendEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(acknowledgement.isDone()).isFalse();

            storage.allowAppend.countDown();
            assertThat(acknowledgement.get(5, TimeUnit.SECONDS).afterEventId())
                    .contains(new EventId(1));
        }
    }

    @Test
    void returnsTheStorageCursorForAnIdenticalRetry() throws Exception {
        RecordingStorage storage = new RecordingStorage(Optional.empty());
        SessionEventRecord retried = event(4, (byte) 4);
        storage.appendResult = new JournalAppendResult(Optional.of(new EventId(9)), List.of());
        SessionReplicationService service = new SessionReplicationService(
                storage, (agentId, sessionId, gap) -> { });

        AgentMessage.SessionSync acknowledgement = service.append(SESSION_ID, List.of(retried));

        assertThat(acknowledgement.afterEventId()).contains(new EventId(9));
        assertThat(storage.appended).containsExactly(retried);
    }

    @Test
    void classifiesStorageFailures() throws Exception {
        for (JournalStorageException.Reason reason : JournalStorageException.Reason.values()) {
            RecordingStorage storage = new RecordingStorage(Optional.empty());
            storage.appendFailure = new JournalStorageException(reason, reason.name());
            SessionReplicationService service = new SessionReplicationService(
                    storage, (agentId, sessionId, gap) -> { });
            SessionReplicationException.Kind expected = switch (reason) {
                case INVALID_APPEND, CONFLICTING_DUPLICATE -> SessionReplicationException.Kind.PROTOCOL;
                case STORED_CORRUPTION, IO_FAILURE, CLOSED -> SessionReplicationException.Kind.INTERNAL;
            };

            assertThatThrownBy(() -> service.append(SESSION_ID, List.of(event(1, (byte) 1))))
                    .isInstanceOfSatisfying(SessionReplicationException.class,
                            failure -> assertThat(failure.kind()).isEqualTo(expected))
                    .hasCause(storage.appendFailure);
        }
    }

    @Test
    void rejectsEmptyBatchAndMissingDurableCursor() throws Exception {
        RecordingStorage storage = new RecordingStorage(Optional.empty());
        storage.appendResult = new JournalAppendResult(Optional.empty(), List.of());
        SessionReplicationService service = new SessionReplicationService(
                storage, (agentId, sessionId, gap) -> { });

        assertThatThrownBy(() -> service.append(SESSION_ID, List.of()))
                .isInstanceOfSatisfying(SessionReplicationException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(SessionReplicationException.Kind.PROTOCOL));
        assertThatThrownBy(() -> service.append(SESSION_ID, List.of(event(1, (byte) 1))))
                .isInstanceOfSatisfying(SessionReplicationException.class,
                        failure -> assertThat(failure.kind())
                                .isEqualTo(SessionReplicationException.Kind.INTERNAL));
    }

    private static AgentMessage.SessionOpen open(Optional<EventId> first, Optional<EventId> last) {
        return new AgentMessage.SessionOpen(
                SESSION_ID, first, last, AgentMessage.SessionState.RUNNING);
    }

    private static SessionEventRecord event(long eventId, byte payload)
            throws AgentProtocolException {
        return EVENTS.decode(EVENTS.encode(
                new EventId(eventId),
                new SessionEventPayload.PtyOutput(
                        ProtocolBytes.copyOf(new byte[]{payload}))));
    }

    private static final class RecordingStorage implements SessionJournalStorage {
        private final Optional<EventId> last;
        private SessionId lastRequestedFor;
        private JournalStorageException appendFailure;
        private JournalAppendResult appendResult;
        private List<SessionEventRecord> appended = List.of();
        private final CountDownLatch appendEntered = new CountDownLatch(1);
        private final CountDownLatch allowAppend = new CountDownLatch(1);
        private boolean blockAppend;

        private RecordingStorage(Optional<EventId> last) {
            this.last = last;
        }

        @Override
        public Optional<EventId> firstEventId(SessionId sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<EventId> lastEventId(SessionId sessionId) {
            lastRequestedFor = sessionId;
            return last;
        }

        @Override
        public JournalAppendResult append(SessionId sessionId, List<SessionEventRecord> records)
                throws JournalStorageException {
            appended = List.copyOf(records);
            appendEntered.countDown();
            if (blockAppend) {
                try {
                    if (!allowAppend.await(5, TimeUnit.SECONDS)) {
                        throw new JournalStorageException(
                                JournalStorageException.Reason.IO_FAILURE,
                                "Timed out waiting to release append");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new JournalStorageException(
                            JournalStorageException.Reason.IO_FAILURE,
                            "Interrupted while waiting to append",
                            failure);
                }
            }
            if (appendFailure != null) {
                throw appendFailure;
            }
            return appendResult;
        }

        @Override
        public JournalReadResult readAfter(SessionId sessionId, Optional<EventId> after)
                throws JournalStorageException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
