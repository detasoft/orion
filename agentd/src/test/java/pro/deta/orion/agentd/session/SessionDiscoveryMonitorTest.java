package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SessionDiscoveryMonitorTest {
    @Test
    void initialEventsOverflowAndPeriodicWakeupsAllRunFullReconciliation() throws Exception {
        QueueEvents events = new QueueEvents(
                SessionDiscoveryMonitor.Trigger.EVENT,
                SessionDiscoveryMonitor.Trigger.OVERFLOW,
                SessionDiscoveryMonitor.Trigger.PERIODIC);
        AtomicInteger reconciliations = new AtomicInteger();
        SessionDiscoveryMonitor monitor = new SessionDiscoveryMonitor(
                reconciliations::incrementAndGet, events, Duration.ofMillis(1));

        monitor.start();
        events.awaitConsumed();
        monitor.close();

        assertThat(reconciliations).hasValue(4);
    }

    @Test
    void continuesAfterAnIndependentReconciliationFailure() throws Exception {
        QueueEvents events = new QueueEvents(
                SessionDiscoveryMonitor.Trigger.EVENT,
                SessionDiscoveryMonitor.Trigger.PERIODIC);
        AtomicInteger attempts = new AtomicInteger();
        SessionDiscoveryMonitor monitor = new SessionDiscoveryMonitor(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("root temporarily unavailable");
            }
        }, events, Duration.ofMillis(1));

        monitor.start();
        events.awaitConsumed();
        monitor.close();

        assertThat(attempts).hasValue(3);
        assertThat(monitor.lastFailure()).isPresent();
    }

    @Test
    void retriesWatchRegistrationAfterTheDirectoryReturns() throws Exception {
        FakeWatchKey invalidated = new FakeWatchKey(false, List.of());
        FakeWatchKey recovered = new FakeWatchKey(true, List.of(new FakeWatchEvent(
                StandardWatchEventKinds.ENTRY_CREATE)));
        FakeWatchService watchService = new FakeWatchService(invalidated);
        AtomicInteger registrations = new AtomicInteger();
        SessionDiscoveryMonitor.WatchRegistrar registrar = () -> {
            if (registrations.incrementAndGet() == 1) {
                throw new IOException("directory is absent");
            }
            return recovered;
        };
        SessionDiscoveryMonitor.EventSource events = new SessionDiscoveryMonitor.WatchServiceEvents(
                watchService, registrar, invalidated);

        assertThat(events.await(Duration.ofMillis(1))).isEqualTo(SessionDiscoveryMonitor.Trigger.EVENT);
        assertThat(events.await(Duration.ofMillis(1))).isEqualTo(SessionDiscoveryMonitor.Trigger.PERIODIC);
        watchService.add(recovered);
        assertThat(events.await(Duration.ofMillis(1))).isEqualTo(SessionDiscoveryMonitor.Trigger.EVENT);
        assertThat(registrations).hasValue(2);
    }

    @Test
    void interruptsWorkerEvenWhenEventSourceCloseFails() throws Exception {
        ThrowingCloseEvents events = new ThrowingCloseEvents();
        SessionDiscoveryMonitor monitor = new SessionDiscoveryMonitor(
                () -> { }, events, Duration.ofSeconds(1));
        monitor.start();
        events.awaitEntered();

        assertThatIOException().isThrownBy(monitor::close).withMessage("close failed");
        assertThat(events.awaitInterrupted()).isTrue();
        assertThatNoException().isThrownBy(monitor::close);
    }

    private static final class QueueEvents implements SessionDiscoveryMonitor.EventSource {
        private final Queue<SessionDiscoveryMonitor.Trigger> triggers = new ArrayDeque<>();
        private final Object lock = new Object();
        private boolean consumed;

        QueueEvents(SessionDiscoveryMonitor.Trigger... triggers) {
            this.triggers.addAll(java.util.List.of(triggers));
        }

        @Override
        public SessionDiscoveryMonitor.Trigger await(Duration interval) throws InterruptedException {
            synchronized (lock) {
                SessionDiscoveryMonitor.Trigger trigger = triggers.poll();
                if (trigger != null) {
                    return trigger;
                }
                consumed = true;
                lock.notifyAll();
                lock.wait();
                return SessionDiscoveryMonitor.Trigger.PERIODIC;
            }
        }

        void awaitConsumed() throws InterruptedException {
            synchronized (lock) {
                while (!consumed) {
                    lock.wait();
                }
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    private static final class ThrowingCloseEvents implements SessionDiscoveryMonitor.EventSource {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public SessionDiscoveryMonitor.Trigger await(Duration interval) throws InterruptedException {
            entered.countDown();
            try {
                Thread.sleep(interval);
                return SessionDiscoveryMonitor.Trigger.PERIODIC;
            } catch (InterruptedException error) {
                interrupted.countDown();
                throw error;
            }
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        }

        boolean awaitInterrupted() throws InterruptedException {
            return interrupted.await(1, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            throw new IOException("close failed");
        }
    }

    private static final class FakeWatchService implements WatchService {
        private final Queue<WatchKey> keys = new ArrayDeque<>();

        FakeWatchService(WatchKey... keys) {
            this.keys.addAll(List.of(keys));
        }

        void add(WatchKey key) {
            keys.add(key);
        }

        @Override
        public WatchKey poll() {
            return keys.poll();
        }

        @Override
        public WatchKey poll(long timeout, TimeUnit unit) {
            return poll();
        }

        @Override
        public WatchKey take() {
            return poll();
        }

        @Override
        public void close() {
        }
    }

    private record FakeWatchEvent(WatchEvent.Kind<Path> kind) implements WatchEvent<Path> {
        @Override
        public int count() {
            return 1;
        }

        @Override
        public Path context() {
            return Path.of("session");
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
            return Path.of("sessions");
        }
    }
}
