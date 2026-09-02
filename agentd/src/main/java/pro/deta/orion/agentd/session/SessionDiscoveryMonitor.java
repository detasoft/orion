package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SessionDiscoveryMonitor implements AutoCloseable {
    private final Reconciler reconciler;
    private final EventSource events;
    private final Duration interval;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Exception> lastFailure = new AtomicReference<>();
    private volatile Thread worker;

    public SessionDiscoveryMonitor(Path sessionsDirectory, SessionDiscovery discovery, Duration interval)
            throws IOException {
        this(discovery::reconcile, new WatchServiceEvents(sessionsDirectory), interval);
    }

    SessionDiscoveryMonitor(Reconciler reconciler, EventSource events, Duration interval) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.events = Objects.requireNonNull(events, "events");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    public synchronized void start() {
        if (worker != null) {
            throw new IllegalStateException("session discovery monitor is already started");
        }
        worker = Thread.ofVirtual().name("agentd-session-discovery").start(this::run);
    }

    public Optional<Exception> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    private void run() {
        reconcile();
        while (!closed.get()) {
            try {
                events.await(interval);
                if (!closed.get()) {
                    reconcile();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException error) {
                lastFailure.set(error);
            }
        }
    }

    private void reconcile() {
        try {
            reconciler.reconcile();
        } catch (IOException | RuntimeException error) {
            lastFailure.set(error);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException closeFailure = null;
        try {
            events.close();
        } catch (IOException error) {
            closeFailure = error;
        }
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            boolean interrupted = false;
            while (current.isAlive()) {
                try {
                    current.join();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    enum Trigger {
        EVENT,
        OVERFLOW,
        PERIODIC
    }

    @FunctionalInterface
    interface Reconciler {
        void reconcile() throws IOException;
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
        private WatchKey key;

        private WatchServiceEvents(Path directory) throws IOException {
            Path normalized = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            WatchService service = FileSystems.getDefault().newWatchService();
            watchService = service;
            registrar = () -> normalized.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            try {
                key = registrar.register();
            } catch (IOException error) {
                try {
                    service.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
                throw error;
            }
        }

        WatchServiceEvents(WatchService watchService, WatchRegistrar registrar, WatchKey key) {
            this.watchService = Objects.requireNonNull(watchService, "watchService");
            this.registrar = Objects.requireNonNull(registrar, "registrar");
            this.key = key;
        }

        @Override
        public Trigger await(Duration interval) throws IOException, InterruptedException {
            tryRegister();
            WatchKey ready = watchService.poll(interval.toNanos(), TimeUnit.NANOSECONDS);
            if (ready == null) {
                tryRegister();
                return Trigger.PERIODIC;
            }
            boolean overflow = false;
            for (WatchEvent<?> event : ready.pollEvents()) {
                overflow |= event.kind() == StandardWatchEventKinds.OVERFLOW;
            }
            if (!ready.reset()) {
                key = null;
                tryRegister();
            }
            return overflow ? Trigger.OVERFLOW : Trigger.EVENT;
        }

        private void tryRegister() {
            if (key != null) {
                return;
            }
            try {
                key = registrar.register();
            } catch (IOException ignored) {
                // Periodic reconciliation continues and the next await retries registration.
            }
        }

        @Override
        public void close() throws IOException {
            if (key != null) {
                key.cancel();
            }
            watchService.close();
        }
    }
}
