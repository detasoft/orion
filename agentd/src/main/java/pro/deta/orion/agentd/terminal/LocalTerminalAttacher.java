package pro.deta.orion.agentd.terminal;

import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agentd.session.ControlCommand;
import pro.deta.orion.agentd.session.ControlEndpoint;
import pro.deta.orion.agentd.session.ControlHostProbe;
import pro.deta.orion.agentd.session.ControlResult;
import pro.deta.orion.agentd.session.HostObservation;
import pro.deta.orion.agentd.session.HostProbe;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionManifest;
import pro.deta.orion.agentd.session.SessionManifestReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class LocalTerminalAttacher {
    private final SessionManifestReader manifests;
    private final HostProbe hosts;
    private final TerminalFactory terminals;
    private final ControlSender controls;
    private final TerminalJournalFollower journal;

    LocalTerminalAttacher(
            SessionManifestReader manifests,
            HostProbe hosts,
            TerminalFactory terminals,
            ControlSender controls
    ) {
        this.manifests = manifests;
        this.hosts = hosts;
        this.terminals = terminals;
        this.controls = controls;
        journal = new TerminalJournalFollower();
    }

    static LocalTerminalAttacher create() {
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
        return new LocalTerminalAttacher(
                new JsonSessionManifestReader(),
                new ControlHostProbe(client),
                PosixTerminal::acquire,
                client::send);
    }

    int attach(Path sessionDirectory, PrintStream errors) {
        Objects.requireNonNull(sessionDirectory, "sessionDirectory");
        Objects.requireNonNull(errors, "errors");
        Path normalized = sessionDirectory.toAbsolutePath().normalize();
        SessionManifest manifest;
        try {
            manifest = manifests.read(normalized);
        } catch (IOException | RuntimeException failure) {
            errors.println(TerminalDiagnostics.bounded(
                    "terminal attach failed: " + TerminalDiagnostics.detail(failure)));
            return 1;
        }
        TerminalJournalFollower.Inspection inspection = journal.inspect(normalized);
        if (inspection instanceof TerminalJournalFollower.Inspection.Failed failed) {
            errors.println(failed.detail());
            return 1;
        }
        if (inspection instanceof TerminalJournalFollower.Inspection.Active) {
            try {
                HostObservation observation = hosts.probe(normalized, manifest);
                if (observation.status() != HostObservation.Status.LIVE) {
                    inspection = journal.inspectUntilTail(normalized);
                    if (inspection instanceof TerminalJournalFollower.Inspection.Active) {
                        errors.println("terminal attach failed: session host is unreachable");
                        return 1;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                errors.println(TerminalDiagnostics.bounded(
                        "terminal attach failed: " + TerminalDiagnostics.detail(failure)));
                return 1;
            }
        }
        if (inspection instanceof TerminalJournalFollower.Inspection.Failed failed) {
            errors.println(failed.detail());
            return 1;
        }

        TerminalDevice terminal;
        try {
            terminal = terminals.acquire();
        } catch (IOException | RuntimeException failure) {
            errors.println(TerminalDiagnostics.bounded(
                    "terminal attach failed: " + TerminalDiagnostics.detail(failure)));
            return 1;
        }
        return runAttached(normalized, manifest, terminal, inspection, errors);
    }

    private int runAttached(
            Path sessionDirectory,
            SessionManifest manifest,
            TerminalDevice terminal,
            TerminalJournalFollower.Inspection inspection,
            PrintStream errors
    ) {
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<ControlResult> controlFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("agentd-terminal-", 0).factory());
        ManualLane lane = new ManualLane(manifest.control(), controls, stopped, controlFailure);
        Future<?> controlTask = null;
        int result;
        try {
            if (inspection instanceof TerminalJournalFollower.Inspection.Active) {
                controlTask = executor.submit(lane::run);
                TerminalSize current = terminal.size();
                if (current.columns() != manifest.currentColumns()
                        || current.rows() != manifest.currentRows()) {
                    lane.resize(current);
                }
                executor.submit(() -> readInput(terminal, lane, stopped, controlFailure));
                executor.submit(() -> watchResize(terminal, lane, stopped, controlFailure));
            }
            TerminalJournalFollower.Result journalResult = journal.follow(
                    sessionDirectory, terminal.output(), stopped::get);
            stopped.set(true);
            if (controlTask != null) {
                await(controlTask);
            }
            if (controlFailure.get() != null) {
                ControlResult failure = controlFailure.get();
                errors.println(TerminalDiagnostics.bounded(
                        "terminal control failed: " + controlDetail(failure)));
                result = 1;
            } else if (journalResult instanceof TerminalJournalFollower.Result.Failed failed) {
                errors.println(failed.detail());
                result = 1;
            } else if (journalResult instanceof TerminalJournalFollower.Result.Exited exited) {
                result = processExitCode(exited.exitCode());
            } else {
                result = 0;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            stopped.set(true);
            errors.println("terminal attach interrupted");
            result = 1;
        } catch (IOException | RuntimeException failure) {
            stopped.set(true);
            errors.println(TerminalDiagnostics.bounded(
                    "terminal attach failed: " + TerminalDiagnostics.detail(failure)));
            result = 1;
        } finally {
            stopped.set(true);
            try {
                terminal.close();
            } catch (IOException failure) {
                errors.println(TerminalDiagnostics.bounded(
                        "terminal restoration failed: " + TerminalDiagnostics.detail(failure)));
                result = 1;
            }
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    errors.println("terminal attach failed: local terminal tasks did not stop");
                    result = 1;
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                errors.println("terminal attach interrupted");
                result = 1;
            }
        }
        return result;
    }

    private static void readInput(
            TerminalDevice terminal,
            ManualLane lane,
            AtomicBoolean stopped,
            AtomicReference<ControlResult> failure
    ) {
        TerminalInputParser parser = new TerminalInputParser();
        byte[] buffer = new byte[4096];
        try {
            while (!stopped.get()) {
                int length = terminal.input().read(buffer);
                if (length < 0) {
                    TerminalInputParser.Result end = parser.finish();
                    lane.input(end.bytes());
                    stopped.set(true);
                    return;
                }
                if (length == 0) {
                    continue;
                }
                TerminalInputParser.Result parsed = parser.accept(Arrays.copyOf(buffer, length));
                lane.input(parsed.bytes());
                if (parsed.detached()) {
                    stopped.set(true);
                    return;
                }
            }
        } catch (IOException | InterruptedException exception) {
            if (!stopped.get()) {
                failure.compareAndSet(null, new ControlResult.Failed(
                        java.util.OptionalLong.empty(),
                        ControlResult.FailureKind.CONNECTION,
                        TerminalDiagnostics.bounded(
                                "terminal input failed: " + TerminalDiagnostics.detail(exception))));
                stopped.set(true);
            }
        }
    }

    private static void watchResize(
            TerminalDevice terminal,
            ManualLane lane,
            AtomicBoolean stopped,
            AtomicReference<ControlResult> failure
    ) {
        try {
            while (!stopped.get()) {
                Optional<TerminalSize> size = terminal.awaitResize(Duration.ofMillis(50));
                if (size.isPresent()) {
                    lane.resize(size.orElseThrow());
                }
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (!stopped.get()) {
                failure.compareAndSet(null, new ControlResult.Failed(
                        java.util.OptionalLong.empty(),
                        ControlResult.FailureKind.CONNECTION,
                        TerminalDiagnostics.bounded(
                                "terminal resize failed: " + TerminalDiagnostics.detail(exception))));
                stopped.set(true);
            }
        }
    }

    private static void await(Future<?> task) throws InterruptedException {
        try {
            task.get();
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("manual control lane failed", exception.getCause());
        }
    }

    private static int processExitCode(int exitCode) {
        return exitCode >= 0 && exitCode <= 255 ? exitCode : 1;
    }

    private static String controlDetail(ControlResult result) {
        return switch (result) {
            case ControlResult.Failed failed -> failed.kind().name().toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ') + ": " + failed.detail();
            case ControlResult.Rejected rejected -> "rejected: " + rejected.detail();
            default -> "unexpected native response";
        };
    }

    private sealed interface ManualAction {
        record Input(byte[] bytes) implements ManualAction {
        }

        record Resize() implements ManualAction {
        }
    }

    private static final class ManualLane {
        private static final int CAPACITY = 64;
        private final ControlEndpoint endpoint;
        private final ControlSender controls;
        private final AtomicBoolean stopped;
        private final AtomicReference<ControlResult> failure;
        private final ArrayBlockingQueue<ManualAction> actions = new ArrayBlockingQueue<>(CAPACITY);
        private final Object resizeLock = new Object();
        private TerminalSize pendingSize;
        private boolean resizeQueued;
        private long sequence;

        private ManualLane(
                ControlEndpoint endpoint,
                ControlSender controls,
                AtomicBoolean stopped,
                AtomicReference<ControlResult> failure
        ) {
            this.endpoint = endpoint;
            this.controls = controls;
            this.stopped = stopped;
            this.failure = failure;
        }

        private void input(byte[] bytes) throws InterruptedException {
            if (bytes.length > 0) {
                actions.put(new ManualAction.Input(Arrays.copyOf(bytes, bytes.length)));
            }
        }

        private void resize(TerminalSize size) throws InterruptedException {
            boolean enqueue = false;
            synchronized (resizeLock) {
                pendingSize = size;
                if (!resizeQueued) {
                    resizeQueued = true;
                    enqueue = true;
                }
            }
            if (enqueue) {
                actions.put(new ManualAction.Resize());
            }
        }

        private void run() {
            try {
                while (!stopped.get() || !actions.isEmpty()) {
                    ManualAction action = actions.poll(50, TimeUnit.MILLISECONDS);
                    if (action == null) {
                        continue;
                    }
                    ControlCommand command = command(action);
                    if (command == null) {
                        continue;
                    }
                    ControlResult result = controls.send(endpoint, command);
                    if (!(result instanceof ControlResult.Received received)
                            || received.operationSequence() != command.operationSequence().orElseThrow()) {
                        failure.compareAndSet(null, result);
                        stopped.set(true);
                        return;
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                failure.compareAndSet(null, new ControlResult.Failed(
                        java.util.OptionalLong.empty(),
                        ControlResult.FailureKind.CONNECTION,
                        TerminalDiagnostics.detail(exception)));
                stopped.set(true);
            }
        }

        private ControlCommand command(ManualAction action) {
            long next = nextSequence();
            if (action instanceof ManualAction.Input input) {
                return new ControlCommand.Input(
                        next,
                        SessionCommandSource.MANUAL,
                        Optional.empty(),
                        UUID.randomUUID(),
                        ProtocolBytes.copyOf(input.bytes()));
            }
            TerminalSize size;
            synchronized (resizeLock) {
                size = pendingSize;
                pendingSize = null;
                resizeQueued = false;
            }
            if (size == null) {
                return null;
            }
            return new ControlCommand.Resize(
                    next,
                    SessionCommandSource.MANUAL,
                    Optional.empty(),
                    size.columns(),
                    size.rows());
        }

        private long nextSequence() {
            sequence++;
            if (sequence == 0 || sequence == -1) {
                sequence = 1;
            }
            return sequence;
        }
    }

    @FunctionalInterface
    interface TerminalFactory {
        TerminalDevice acquire() throws IOException;
    }

    @FunctionalInterface
    interface ControlSender {
        ControlResult send(ControlEndpoint endpoint, ControlCommand command);
    }
}
