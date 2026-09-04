package pro.deta.orion.agentd.journal;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
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

    public Trigger await() throws IOException, InterruptedException {
        if (closed.get()) {
            return closed();
        }
        try {
            Trigger trigger = events.await(pollInterval);
            return closed.get() ? closed() : trigger;
        } catch (ClosedWatchServiceException exception) {
            if (closed.get()) {
                return closed();
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

    static Trigger timeout() {
        return trigger(TriggerKind.TIMEOUT);
    }

    static Trigger watchRestored() {
        return trigger(TriggerKind.WATCH_RESTORED);
    }

    static Trigger watchUnavailable() {
        return trigger(TriggerKind.WATCH_UNAVAILABLE);
    }

    static Trigger closed() {
        return trigger(TriggerKind.CLOSED);
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

    private static Trigger trigger(TriggerKind kind) {
        return new Trigger(kind, Optional.empty());
    }

    public enum TriggerKind {
        CREATED,
        MODIFIED,
        DELETED,
        OVERFLOW,
        TIMEOUT,
        WATCH_RESTORED,
        WATCH_UNAVAILABLE,
        CLOSED
    }

    public record Trigger(TriggerKind kind, Optional<Path> relativePath) {
        public Trigger {
            kind = Objects.requireNonNull(kind, "kind");
            relativePath = Objects.requireNonNull(relativePath, "relativePath");
            boolean entry = kind == TriggerKind.CREATED
                    || kind == TriggerKind.MODIFIED
                    || kind == TriggerKind.DELETED;
            if (entry && relativePath.isEmpty()) {
                throw new IllegalArgumentException("entry trigger must have a relative path");
            }
            if (entry && relativePath.orElseThrow().isAbsolute()) {
                throw new IllegalArgumentException("entry trigger path must be relative");
            }
            if (!entry && relativePath.isPresent()) {
                throw new IllegalArgumentException("non-entry trigger must not have a path");
            }
        }
    }

    interface EventSource extends AutoCloseable {
        Trigger await(Duration interval) throws IOException, InterruptedException;

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
        private final Queue<Trigger> pending = new ArrayDeque<>();
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
        public Trigger await(Duration interval) throws InterruptedException {
            Trigger queued = pending.poll();
            if (queued != null) {
                return queued;
            }
            if (key == null) {
                WatchKey ready = watchService.poll(interval.toNanos(), TimeUnit.NANOSECONDS);
                if (ready == null) {
                    return tryRegister();
                }
                return accept(ready);
            }
            WatchKey ready = watchService.poll(interval.toNanos(), TimeUnit.NANOSECONDS);
            if (ready == null) {
                return timeout();
            }
            return accept(ready);
        }

        private Trigger accept(WatchKey ready) {
            for (WatchEvent<?> event : ready.pollEvents()) {
                add(event);
            }
            if (!ready.reset()) {
                key = null;
                pending.add(tryRegister());
            }
            Trigger trigger = pending.poll();
            return trigger == null ? timeout() : trigger;
        }

        private Trigger tryRegister() {
            try {
                key = registrar.register();
                return watchRestored();
            } catch (IOException | RuntimeException ignored) {
                key = null;
                return watchUnavailable();
            }
        }

        private void add(WatchEvent<?> event) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                pending.add(trigger(TriggerKind.OVERFLOW));
                return;
            }
            TriggerKind triggerKind;
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                triggerKind = TriggerKind.CREATED;
            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                triggerKind = TriggerKind.MODIFIED;
            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                triggerKind = TriggerKind.DELETED;
            } else {
                return;
            }
            if (!(event.context() instanceof Path context) || context.isAbsolute()) {
                pending.add(trigger(TriggerKind.OVERFLOW));
                return;
            }
            pending.add(new Trigger(triggerKind, Optional.of(context)));
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
