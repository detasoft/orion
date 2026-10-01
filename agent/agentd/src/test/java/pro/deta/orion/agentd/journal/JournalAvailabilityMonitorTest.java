package pro.deta.orion.agentd.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalAvailabilityMonitorTest {
    private static final SessionEventCodec CODEC = new SessionEventCodec(AgentProtocolLimits.defaults());

    @TempDir
    Path temporaryDirectory;

    @Test
    void coalescesAllEventsFromAWatchKeyIntoOneRescan() throws Exception {
        FakeWatchKey key = new FakeWatchKey(true, List.of(
                new FakeWatchEvent(StandardWatchEventKinds.ENTRY_CREATE, Path.of("00000001.cbor")),
                new FakeWatchEvent(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("00000001.cbor")),
                new FakeWatchEvent(StandardWatchEventKinds.ENTRY_DELETE, Path.of("00000001.cbor")),
                new FakeWatchEvent(StandardWatchEventKinds.OVERFLOW, null)));
        FakeWatchService watchService = new FakeWatchService(key);
        JournalAvailabilityMonitor.EventSource events =
                new JournalAvailabilityMonitor.WatchServiceEvents(watchService, () -> key, key);
        JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(events, Duration.ofSeconds(1));

        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(watchService.timedPolls()).hasValue(1);
        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(watchService.timedPolls()).hasValue(2);
    }

    @Test
    void treatsAnUnusablePathContextAsARescan() throws Exception {
        FakeWatchKey key = new FakeWatchKey(true, List.of(
                new FakeWatchEvent<>(StandardWatchEventKinds.ENTRY_CREATE, null),
                new FakeWatchEvent<>(StandardWatchEventKinds.ENTRY_MODIFY, temporaryDirectory)));
        FakeWatchService watchService = new FakeWatchService(key);
        JournalAvailabilityMonitor.EventSource events = new JournalAvailabilityMonitor.WatchServiceEvents(
                watchService,
                () -> key,
                key);
        JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(events, Duration.ofSeconds(1));

        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(watchService.timedPolls()).hasValue(2);
    }

    @Test
    void rejectsAPollIntervalThatCannotBeRepresentedInNanoseconds() {
        Duration tooLarge = Duration.ofSeconds(Long.MAX_VALUE);

        assertThatThrownBy(() -> new JournalAvailabilityMonitor(
                new RecordingEvents(JournalAvailabilityMonitor.Wakeup.RESCAN),
                tooLarge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pollInterval is too large")
                .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    void usesAnExactOneHundredMillisecondDefaultAndReturnsPeriodicTimeout() throws Exception {
        RecordingEvents events = new RecordingEvents(JournalAvailabilityMonitor.Wakeup.RESCAN);
        JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(events);

        JournalAvailabilityMonitor.Wakeup trigger = monitor.await();

        assertThat(trigger).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(events.lastInterval()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void attemptsOneReregistrationAndReportsRecoveryAfterAnInvalidKey() throws Exception {
        FakeWatchKey invalid = new FakeWatchKey(false, List.of());
        FakeWatchKey recovered = new FakeWatchKey(true, List.of());
        AtomicInteger registrations = new AtomicInteger();
        JournalAvailabilityMonitor.EventSource events = new JournalAvailabilityMonitor.WatchServiceEvents(
                new FakeWatchService(invalid),
                () -> {
                    registrations.incrementAndGet();
                    return recovered;
                },
                invalid);
        JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(events, Duration.ofSeconds(1));

        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(registrations).hasValue(1);
    }

    @Test
    void boundsFailedReregistrationToOneAttemptAndReportsUnavailableWatch() throws Exception {
        FakeWatchKey invalid = new FakeWatchKey(false, List.of());
        AtomicInteger registrations = new AtomicInteger();
        FakeWatchService watchService = new FakeWatchService(invalid);
        JournalAvailabilityMonitor.EventSource events = new JournalAvailabilityMonitor.WatchServiceEvents(
                watchService,
                () -> {
                    registrations.incrementAndGet();
                    throw new IOException("journal directory disappeared");
                },
                invalid);
        JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(events, Duration.ofSeconds(1));

        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(registrations).hasValue(1);
        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(registrations).hasValue(2);
        assertThat(watchService.lastPollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(watchService.timedPolls()).hasValue(2);
    }

    @Test
    void closeUnblocksAwaitAndLeavesAStableClosedOutcome() throws Exception {
        BlockingEvents events = new BlockingEvents();
        JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(events, Duration.ofSeconds(30));

        JournalAvailabilityMonitor.Wakeup first;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JournalAvailabilityMonitor.Wakeup> waiting = executor.submit(monitor::await);
            events.awaitEntered();
            monitor.close();
            first = waiting.get(1, TimeUnit.SECONDS);
        }

        assertThat(first).isEqualTo(JournalAvailabilityMonitor.Wakeup.CLOSED);
        assertThat(monitor.await()).isEqualTo(JournalAvailabilityMonitor.Wakeup.CLOSED);
    }

    @Test
    void appendWakeupCompletesAPreviouslyPartialActiveRecord() throws Exception {
        byte[] record = CODEC.encode(
                new EventId(1),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[]{1, 2})));
        Path active = temporaryDirectory.resolve("00000001.cbor");
        Files.write(active, Arrays.copyOf(record, record.length - 1));
        FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
        JournalReadLimits limits = new JournalReadLimits(
                10,
                AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES);
        JournalReadPage partial = reader.readPage(
                temporaryDirectory,
                Optional.empty(),
                Optional.empty(),
                limits);

        JournalReadPosition position = partial.nextPosition().orElseThrow();
        WakeupRead result;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(temporaryDirectory)) {
            Future<WakeupRead> following = executor.submit(() -> {
                JournalAvailabilityMonitor.Wakeup wakeup = monitor.await();
                JournalReadPage completed = reader.readPage(
                        temporaryDirectory,
                        position.lastEventId(),
                        Optional.of(position),
                        limits);
                return new WakeupRead(wakeup, completed);
            });
            Files.write(
                    active,
                    Arrays.copyOfRange(record, record.length - 1, record.length),
                    StandardOpenOption.APPEND);
            result = following.get(1, TimeUnit.SECONDS);
        }

        assertThat(partial.boundary()).isEqualTo(JournalReadBoundary.INCOMPLETE_TAIL);
        assertThat(result.wakeup()).isEqualTo(JournalAvailabilityMonitor.Wakeup.RESCAN);
        assertThat(result.page().records()).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1));
        assertThat(result.page().boundary()).isEqualTo(JournalReadBoundary.COMPLETE);
    }

    private record WakeupRead(JournalAvailabilityMonitor.Wakeup wakeup, JournalReadPage page) {
    }

    private static final class RecordingEvents implements JournalAvailabilityMonitor.EventSource {
        private final JournalAvailabilityMonitor.Wakeup trigger;
        private final AtomicReference<Duration> lastInterval = new AtomicReference<>();

        RecordingEvents(JournalAvailabilityMonitor.Wakeup trigger) {
            this.trigger = trigger;
        }

        @Override
        public JournalAvailabilityMonitor.Wakeup await(Duration interval) {
            lastInterval.set(interval);
            return trigger;
        }

        Duration lastInterval() {
            return lastInterval.get();
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingEvents implements JournalAvailabilityMonitor.EventSource {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public JournalAvailabilityMonitor.Wakeup await(Duration interval) throws InterruptedException {
            entered.countDown();
            closed.await();
            return JournalAvailabilityMonitor.Wakeup.RESCAN;
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class FakeWatchService implements WatchService {
        private final Queue<WatchKey> keys = new ArrayDeque<>();
        private final AtomicReference<Duration> lastPollInterval = new AtomicReference<>();
        private final AtomicInteger timedPolls = new AtomicInteger();

        FakeWatchService(WatchKey... keys) {
            this.keys.addAll(List.of(keys));
        }

        @Override
        public WatchKey poll() {
            return keys.poll();
        }

        @Override
        public WatchKey poll(long timeout, TimeUnit unit) {
            lastPollInterval.set(Duration.ofNanos(unit.toNanos(timeout)));
            timedPolls.incrementAndGet();
            return poll();
        }

        Duration lastPollInterval() {
            return lastPollInterval.get();
        }

        AtomicInteger timedPolls() {
            return timedPolls;
        }

        @Override
        public WatchKey take() {
            return poll();
        }

        @Override
        public void close() {
        }
    }

    private record FakeWatchEvent<T>(WatchEvent.Kind<T> kind, T context) implements WatchEvent<T> {
        @Override
        public int count() {
            return 1;
        }
    }

    private static final class FakeWatchKey implements WatchKey {
        private final boolean reset;
        private final List<WatchEvent<?>> events;

        FakeWatchKey(boolean reset, List<WatchEvent<?>> events) {
            this.reset = reset;
            this.events = events;
        }

        @Override
        public boolean isValid() {
            return reset;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            return events;
        }

        @Override
        public boolean reset() {
            return reset;
        }

        @Override
        public void cancel() {
        }

        @Override
        public Watchable watchable() {
            return Path.of("journal");
        }
    }
}
