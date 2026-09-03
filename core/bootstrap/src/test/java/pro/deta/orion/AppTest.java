package pro.deta.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.state.AggregateLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.util.OrionProvider;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppTest {
    @TempDir
    private Path tempDir;

    @Test
    void runWaitsUntilLifecycleShutdown() throws Exception {
        try (TestLifecycleContext context = new TestLifecycleContext()) {
            FutureTask<Integer> run = new FutureTask<>(() -> App.run(context.lifecycle(), false));
            Thread appThread = new Thread(run, "app-run-test");
            appThread.start();

            context.lifecycle().waitForStarting();
            assertFalse(run.isDone());

            context.lifecycle().beginShutdown();

            assertEquals(0, run.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void runReturnsErrorWhenStartupFails() {
        try (TestLifecycleContext context = new TestLifecycleContext(true)) {
            assertEquals(1, App.run(context.lifecycle(), false));
        }
    }

    @Test
    void runCommandDelegatesStartToServiceManager() throws Exception {
        RecordingLauncher launcher = new RecordingLauncher(new RecordingProcess(42));
        OrionServiceManager manager = new OrionServiceManager(
                new OrionServiceManager.Settings(
                        "orion",
                        tempDir,
                        tempDir.resolve("orion.pid"),
                        tempDir.resolve("orion.log"),
                        Duration.ofSeconds(1),
                        "java",
                        "-Xmx256m",
                        tempDir.resolve("orion.jar")
                ),
                launcher,
                pid -> Optional.empty()
        );

        int exitCode = App.runCommand(
                new String[]{"start", "--config", "config.yml"},
                output(),
                output(),
                unusedVerifier(),
                () -> manager
        );

        assertEquals(0, exitCode);
        assertEquals(
                List.of("java", "-Xmx256m", "-jar", tempDir.resolve("orion.jar").toString(), "run", "--config", "config.yml"),
                launcher.command
        );
    }

    @Test
    void helpDoesNotCreateServiceManager() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = App.runCommand(
                new String[]{"--help"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                output(),
                unusedVerifier(),
                () -> {
                    throw new AssertionError("service manager should not be created for help");
                }
        );

        assertEquals(0, exitCode);
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static ReleaseVerifier unusedVerifier() {
        return new ReleaseVerifier(command -> {
            throw new AssertionError("verification should not be called");
        }, (uri, destination) -> {
            throw new AssertionError("download should not be called");
        });
    }

    private static final class TestLifecycleContext implements AutoCloseable {
        private final OrionExecutor executor = new OrionExecutor(4, new OrionThreadFactory());
        private final OrionEventManager eventManager = new OrionEventManager();
        private final OrionApplicationLifecycle lifecycle;

        private TestLifecycleContext() {
            this(false);
        }

        private TestLifecycleContext(boolean failStart) {
            RecordingServiceLifecycle service = new RecordingServiceLifecycle(failStart);
            ServiceLifecycleStateMachineAdapter serviceMachine =
                    new ServiceLifecycleStateMachineAdapter("service", service);
            AggregateStateMachine runtime = AggregateLifecycleStateMachineAdapter.define("runtime")
                    .child("service", serviceMachine.stateMachine())
                    .buildAggregateStateMachine();
            AtomicReference<OrionApplicationLifecycle> lifecycleRef = new AtomicReference<>();
            OrionProvider provider = new OrionProvider(
                    lifecycleRef::get,
                    () -> eventManager,
                    () -> executor);
            lifecycle = new OrionApplicationLifecycle(
                    runtime,
                    provider);
            lifecycleRef.set(lifecycle);
        }

        private OrionApplicationLifecycle lifecycle() {
            return lifecycle;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class RecordingServiceLifecycle implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
        private final boolean failStart;
        private boolean running;

        private RecordingServiceLifecycle(boolean failStart) {
            this.failStart = failStart;
        }

        @Override
        public void onStart() {
            if (failStart) {
                throw new IllegalStateException("boom");
            }
            running = true;
        }

        @Override
        public void onStop() {
            running = false;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private static final class RecordingLauncher implements OrionServiceManager.ProcessLauncher {
        private final OrionServiceManager.ManagedProcess process;
        private List<String> command;

        private RecordingLauncher(OrionServiceManager.ManagedProcess process) {
            this.process = process;
        }

        @Override
        public OrionServiceManager.ManagedProcess start(List<String> command) {
            this.command = command;
            return process;
        }
    }

    private static final class RecordingProcess implements OrionServiceManager.ManagedProcess {
        private final long pid;

        private RecordingProcess(long pid) {
            this.pid = pid;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public boolean destroy() {
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            return true;
        }

        @Override
        public boolean waitFor(Duration timeout) {
            return true;
        }
    }
}
