package pro.deta.orion.git.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitDaemonServerTest {
    @Test
    void servesReceivePackFromAnIsolatedRootOnADynamicPort(@TempDir Path directory) throws Exception {
        GitServer server = GitServers.git();

        try (server) {
            GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");
            URI uri = URI.create(remote.uri());
            assertThat(uri.getHost()).isEqualTo("127.0.0.1");
            assertThat(uri.getPort()).isPositive();

            try (GitWorkTree source = GitClients.jgit().init(directory.resolve("source"))) {
                source.writeFile("README.md", "canonical daemon\n");
                source.add("README.md");
                source.commit("initial");
                source.addRemote("origin", remote);
                source.push("origin", "main");
            }

            assertThat(server.snapshot(remote).refs()).containsKey("refs/heads/main");
            assertThat(server.diagnostics()).contains("git version ").contains("127.0.0.1:");
        }
    }

    @Test
    void rejectsRepositoriesOutsideItsRoot(@TempDir Path directory) throws Exception {
        try (GitServer server = GitServers.git()) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> server.createRemoteRepository(directory, "../escaped.git"))
                    .withMessageContaining("one path segment");
        }
    }

    @Test
    void retriesWithAnotherDynamicPortAfterABindCollision(@TempDir Path directory) throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int available = availablePortOtherThan(occupied.getLocalPort());
            int[] ports = {occupied.getLocalPort(), available};
            AtomicInteger index = new AtomicInteger();

            try (GitServer server = new GitDaemonServer("git", () -> ports[index.getAndIncrement()])) {
                GitRemoteRepository remote = server.createRemoteRepository(directory, "remote.git");

                assertThat(URI.create(remote.uri()).getPort()).isEqualTo(available);
                assertThat(server.diagnostics()).contains("start attempt 1").contains("start attempt 2");
            }
        }
    }

    private static int availablePortOtherThan(int excluded) throws Exception {
        int port;
        do {
            try (ServerSocket candidate = new ServerSocket()) {
                candidate.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                port = candidate.getLocalPort();
            }
        } while (port == excluded);
        return port;
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedCloseFinishesShutdown(boolean stalledShutdown, @TempDir Path directory) throws Exception {
        try (DaemonInvocation invocation = new DaemonInvocation(directory, true, stalledShutdown)) {
            invocation.server.createRemoteRepository(directory, "remote.git");
            ProcessHandle child = invocation.awaitChild();
            invocation.call(true, invocation.server::close);

            invocation.awaitFinished();

            assertThat(invocation.failure.get()).isNull();
            assertThat(invocation.interrupted).isTrue();
            assertThat(child.isAlive()).isFalse();
            invocation.server.close();
        }
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void interruptionDuringGracefulShutdownStillForcesTermination(@TempDir Path directory) throws Exception {
        try (DaemonInvocation invocation = new DaemonInvocation(directory, true, true)) {
            invocation.server.createRemoteRepository(directory, "remote.git");
            ProcessHandle child = invocation.awaitChild();
            invocation.call(false, invocation.server::close);
            awaitFile(directory.resolve("terminating"));

            invocation.caller.interrupt();
            invocation.awaitFinished();

            assertThat(invocation.failure.get()).isNull();
            assertThat(invocation.interrupted).isTrue();
            assertThat(child.isAlive()).isFalse();
        }
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @Test
    void interruptedStartupStopsChildAndPreservesOriginalFailure(@TempDir Path directory) throws Exception {
        try (DaemonInvocation invocation = new DaemonInvocation(directory, false, true)) {
            invocation.call(false, () -> invocation.server.createRemoteRepository(directory, "remote.git"));
            ProcessHandle child = invocation.awaitChild();

            invocation.caller.interrupt();
            invocation.awaitFinished();

            assertThat(invocation.failure.get()).isInstanceOf(IOException.class)
                    .hasMessage("Interrupted while waiting for canonical git daemon")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(invocation.interrupted).isTrue();
            assertThat(child.isAlive()).isFalse();
            assertThat(invocation.server.diagnostics()).doesNotContain("start attempt 2");
        }
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(file).exists();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final class DaemonInvocation implements AutoCloseable {
        private final Path directory;
        private final GitDaemonServer server;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private Thread caller;

        private DaemonInvocation(Path directory, boolean ready, boolean stalledShutdown) throws IOException {
            this.directory = directory;
            Path executable = directory.resolve("git-fixture");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String childCommand = shellQuote(java) + " -cp " + shellQuote(System.getProperty("java.class.path"))
                    + " 'pro.deta.orion.git.workflow.GitDaemonServerTest$DaemonChild' "
                    + shellQuote(directory.toString()) + " " + ready + " " + stalledShutdown;
            Files.writeString(executable, "#!/bin/sh\n"
                    + "for argument in \"$@\"; do\n"
                    + "  if [ \"$argument\" = daemon ]; then exec " + childCommand + "; fi\n"
                    + "done\nexec git \"$@\"\n");
            assertThat(executable.toFile().setExecutable(true)).isTrue();
            server = new GitDaemonServer(executable.toString());
        }

        private void call(boolean initiallyInterrupted, ThrowingAction action) {
            caller = Thread.ofPlatform().start(() -> {
                if (initiallyInterrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    action.run();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
        }

        private ProcessHandle awaitChild() throws Exception {
            awaitFile(directory.resolve("started"));
            return ProcessHandle.of(Long.parseLong(Files.readString(directory.resolve("pid"))))
                    .orElseThrow();
        }

        private void awaitFinished() throws InterruptedException {
            caller.join(Duration.ofSeconds(10));
            assertThat(caller.isAlive()).isFalse();
        }

        @Override
        public void close() throws Exception {
            if (caller != null && caller.isAlive()) {
                caller.interrupt();
            }
            Path pidFile = directory.resolve("pid");
            if (Files.exists(pidFile)) {
                ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElse(null);
                if (child != null && child.isAlive()) {
                    child.destroyForcibly();
                    child.onExit().get(5, TimeUnit.SECONDS);
                }
            }
            if (caller != null) {
                caller.join(Duration.ofSeconds(10));
            }
            server.close();
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    public static final class DaemonChild {
        public static void main(String[] arguments) throws Exception {
            Path directory = Path.of(arguments[0]);
            if (Boolean.parseBoolean(arguments[2])) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Files.writeString(directory.resolve("terminating"), "");
                        new CountDownLatch(1).await();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }));
            }
            Files.writeString(directory.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
            if (Boolean.parseBoolean(arguments[1])) {
                System.out.println("Ready to rumble");
            }
            Files.writeString(directory.resolve("started"), "");
            new CountDownLatch(1).await();
        }
    }
}
