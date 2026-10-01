package pro.deta.orion.agentd.journal;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JournalAvailabilityMonitor implements AutoCloseable {
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);
    private final EventSource events;
    private final Duration pollInterval;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JournalAvailabilityMonitor(Path journalDirectory) throws IOException {
        this(openEvents(journalDirectory, DEFAULT_POLL_INTERVAL), DEFAULT_POLL_INTERVAL);
    }

    public JournalAvailabilityMonitor(Path journalDirectory, Duration pollInterval) throws IOException {
        this(openEvents(journalDirectory, pollInterval), pollInterval);
    }

    JournalAvailabilityMonitor(EventSource events) {
        this(events, DEFAULT_POLL_INTERVAL);
    }

    JournalAvailabilityMonitor(EventSource events, Duration pollInterval) {
        this.events = Objects.requireNonNull(events, "events");
        this.pollInterval = requirePositive(pollInterval);
    }

    public Wakeup await() throws IOException, InterruptedException {
        if (closed.get()) {
            return Wakeup.CLOSED;
        }
        try {
            Wakeup wakeup = events.await(pollInterval);
            return closed.get() ? Wakeup.CLOSED : wakeup;
        } catch (ClosedWatchServiceException exception) {
            if (closed.get()) {
                return Wakeup.CLOSED;
            }
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            events.close();
        }
    }

    private static EventSource openEvents(Path journalDirectory, Duration pollInterval) throws IOException {
        requirePositive(pollInterval);
        return new WatchServiceEvents(journalDirectory);
    }

    private static Duration requirePositive(Duration pollInterval) {
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        try {
            pollInterval.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("pollInterval is too large", exception);
        }
        return pollInterval;
    }

    public enum Wakeup {
        RESCAN,
        CLOSED
    }

    interface EventSource extends AutoCloseable {
        Wakeup await(Duration interval) throws IOException, InterruptedException;

        @Override
        void close() throws IOException;
    }

    @FunctionalInterface
    interface WatchRegistrar {
        WatchKey register() throws IOException;
    }

    static final class WatchServiceEvents implements EventSource {
        private final WatchService watchService;
        private final WatchRegistrar registrar;
        private WatchKey key;

        private WatchServiceEvents(Path directory) throws IOException {
            Path normalized = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
            WatchService service = FileSystems.getDefault().newWatchService();
            watchService = service;
            registrar = () -> normalized.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            try {
                key = registrar.register();
            } catch (IOException | RuntimeException exception) {
                try {
                    service.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
        }

        WatchServiceEvents(WatchService watchService, WatchRegistrar registrar, WatchKey key) {
            this.watchService = Objects.requireNonNull(watchService, "watchService");
            this.registrar = Objects.requireNonNull(registrar, "registrar");
            this.key = Objects.requireNonNull(key, "key");
        }

        @Override
        public Wakeup await(Duration interval) throws InterruptedException {
            if (key == null) {
                WatchKey ready = watchService.poll(interval.toNanos(), TimeUnit.NANOSECONDS);
                if (ready == null) {
                    tryRegister();
                    return Wakeup.RESCAN;
                }
                return accept(ready);
            }
            WatchKey ready = watchService.poll(interval.toNanos(), TimeUnit.NANOSECONDS);
            if (ready == null) {
                return Wakeup.RESCAN;
            }
            return accept(ready);
        }

        private Wakeup accept(WatchKey ready) {
            ready.pollEvents();
            if (!ready.reset()) {
                key = null;
                tryRegister();
            }
            return Wakeup.RESCAN;
        }

        private void tryRegister() {
            try {
                key = registrar.register();
            } catch (IOException | RuntimeException ignored) {
                key = null;
            }
        }

        @Override
        public void close() throws IOException {
            WatchKey current = key;
            if (current != null) {
                current.cancel();
            }
            watchService.close();
        }
    }
}
