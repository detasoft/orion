package pro.deta.orion.command.terminal;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandQuery;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.resource.ScopedResourceCatalogResult;
import pro.deta.orion.command.resource.ScopedResourceResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InteractiveTerminalTest {
    @Test
    void navigatesRendersHelpAndDispatchesOnlyOrionRequests() throws Exception {
        List<CommandRequest> requests = new ArrayList<>();
        CommandDispatcher dispatcher = request -> {
            requests.add(request);
            return new CommandResult.Message("handled: " + request.commandLine());
        };
        CountDownLatch promptDelivered = new CountDownLatch(1);
        AtomicBoolean resultDelivered = new AtomicBoolean();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override
            public void write(byte[] bytes, int offset, int length) {
                String value = new String(bytes, offset, length, StandardCharsets.UTF_8);
                if (value.contains("handled:")) {
                    resultDelivered.set(true);
                } else if (resultDelivered.get() && value.contains("[alice@orion] > ")) {
                    promptDelivered.countDown();
                }
                super.write(bytes, offset, length);
            }
        };
        InteractiveTerminal terminal = terminal(dispatcher, directExecutor(), output, tree());
        terminal.resize(96);
        java.io.PipedInputStream input = new java.io.PipedInputStream();
        java.io.PipedOutputStream client = new java.io.PipedOutputStream(input);
        Thread reader = Thread.ofVirtual().start(() -> terminal.run(input));

        client.write("/session\r?\r..\rhelp\rtouch /tmp/x; echo $(id) | cat >x `id`\r"
                .getBytes(StandardCharsets.UTF_8));
        client.flush();
        assertThat(promptDelivered.await(5, TimeUnit.SECONDS)).isTrue();
        client.write("quit\r".getBytes(StandardCharsets.UTF_8));
        client.flush();
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(reader.isAlive()).isFalse();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("[alice@orion] > ", "[alice@orion /session] > ", "show", "session/")
                .contains("handled: touch /tmp/x; echo $(id) | cat >x `id`");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.commandLine()).isEqualTo("touch /tmp/x; echo $(id) | cat >x `id`");
            assertThat(request.context().currentPath()).isEqualTo(CommandPath.root());
            assertThat(request.context().presentation().interactive()).isTrue();
            assertThat(request.context().presentation().ansi()).isFalse();
            assertThat(request.context().presentation().terminalColumns()).isEqualTo(96);
            assertThat(request.context().requestId()).isEqualTo("request-1");
            assertThat(request.context().sessionId()).isEqualTo("session-1");
            assertThat(request.context().sourceAddress()).isEqualTo("127.0.0.1");
            assertThat(request.context().auditMetadata()).containsEntry("transport", "ssh-shell");
        });
    }

    @Test
    void completesVisibleEntriesAndHonorsCtrlDOnlyAtAnEmptyPrompt() {
        List<String> lines = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveTerminal terminal = terminal(
                request -> {
                    lines.add(request.commandLine());
                    return new CommandResult.Message("ok");
                },
                directExecutor(),
                output,
                tree());

        int exit = terminal.run(input("se\t\rvalue\u0004\r\u0004"));

        assertThat(exit).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("session/");
        assertThat(lines).containsExactly("value");
    }

    @Test
    void idleCtrlCClearsTheLineBeforeTheNextCommand() {
        List<String> lines = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveTerminal terminal = terminal(
                request -> {
                    lines.add(request.commandLine());
                    return new CommandResult.Message("ok");
                },
                directExecutor(),
                output,
                tree());

        assertThat(terminal.run(input("partial\u0003value\r\u0004"))).isZero();

        assertThat(lines).containsExactly("value");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("^C");
    }

    @Test
    void ctrlCCancelsAndInterruptsAnActiveCommandWithoutStoppingTheReader() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CommandDispatcher dispatcher = request -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new CommandResult.Message("late result");
        };
        try (ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.io.PipedInputStream input = new java.io.PipedInputStream();
            java.io.PipedOutputStream client = new java.io.PipedOutputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InteractiveTerminal terminal = terminal(dispatcher, executor, output, tree());
            Thread reader = Thread.ofVirtual().start(() -> terminal.run(input));

            client.write("show\r".getBytes(StandardCharsets.UTF_8));
            client.flush();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            client.write("ignored input".getBytes(StandardCharsets.UTF_8));
            client.write(new byte[]{3});
            client.flush();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            client.write("quit\r".getBytes(StandardCharsets.UTF_8));
            client.flush();
            reader.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(reader.isAlive()).isFalse();
            assertThat(output.toString(StandardCharsets.UTF_8))
                    .contains("CANCELLED: Command was cancelled")
                    .contains("\u0007")
                    .doesNotContain("ignored input")
                    .doesNotContain("late result");
        }
    }

    @Test
    void ctrlCResumesParsingTheNextCommandFromTheSameInputChunk() throws Exception {
        CountDownLatch nextStarted = new CountDownLatch(1);
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        CommandDispatcher dispatcher = request -> {
            lines.add(request.commandLine());
            if (request.commandLine().equals("next")) {
                nextStarted.countDown();
                return new CommandResult.Message("next result");
            }
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult.Message("cancelled result");
        };
        try (ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            InteractiveTerminal terminal = terminal(
                    dispatcher,
                    executor,
                    new ByteArrayOutputStream(),
                    tree());
            ChunkThenBlockingInput input = new ChunkThenBlockingInput("first\r\u0003next\r");
            Thread reader = Thread.ofVirtual().start(() -> terminal.run(input));

            assertThat(nextStarted.await(5, TimeUnit.SECONDS)).isTrue();
            terminal.close();
            reader.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(reader.isAlive()).isFalse();
            assertThat(lines).endsWith("next").allMatch(line -> line.equals("first") || line.equals("next"));
            assertThat(lines).filteredOn("next"::equals).hasSize(1);
        }
    }

    @Test
    void doesNotDispatchAgainUntilThePreviousResultAndPromptAreWritten() throws Exception {
        CountDownLatch firstResultWriting = new CountDownLatch(1);
        CountDownLatch releaseFirstResult = new CountDownLatch(1);
        CountDownLatch promptAfterFirst = new CountDownLatch(1);
        CountDownLatch ignoredInputBell = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        CommandDispatcher dispatcher = request -> {
            lines.add(request.commandLine());
            if (dispatches.incrementAndGet() == 2) {
                secondStarted.countDown();
            }
            return new CommandResult.Message(request.commandLine());
        };
        BlockingResultOutput output = new BlockingResultOutput(
                firstResultWriting,
                releaseFirstResult,
                promptAfterFirst,
                ignoredInputBell);
        try (ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.io.PipedInputStream input = new java.io.PipedInputStream();
            java.io.PipedOutputStream client = new java.io.PipedOutputStream(input);
            InteractiveTerminal terminal = terminal(dispatcher, executor, output, tree());
            Thread reader = Thread.ofVirtual().start(() -> terminal.run(input));

            client.write("first\r".getBytes(StandardCharsets.UTF_8));
            client.flush();
            assertThat(firstResultWriting.await(5, TimeUnit.SECONDS)).isTrue();
            client.write("second\r".getBytes(StandardCharsets.UTF_8));
            client.flush();
            assertThat(secondStarted.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirstResult.countDown();
            assertThat(promptAfterFirst.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ignoredInputBell.await(5, TimeUnit.SECONDS)).isTrue();
            client.write("second\r".getBytes(StandardCharsets.UTF_8));
            client.flush();
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            terminal.close();
            reader.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(reader.isAlive()).isFalse();
            assertThat(dispatches.get()).isEqualTo(2);
            assertThat(lines).containsExactly("first", "second");
        }
    }

    @Test
    void deliversResultsOnAVirtualThreadWithoutParkingTheHandlerExecutor() throws Exception {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CountDownLatch resultWritten = new CountDownLatch(1);
        CountDownLatch releaseResult = new CountDownLatch(1);
        AtomicReference<Thread> resultThread = new AtomicReference<>();
        CommandDispatcher dispatcher = request -> {
            handlerThread.set(Thread.currentThread());
            return new CommandResult.Message("done");
        };
        OutputStream output = new ByteArrayOutputStream() {
            @Override
            public void write(byte[] bytes, int offset, int length) {
                if (new String(bytes, offset, length, StandardCharsets.UTF_8).equals("done\n")) {
                    resultThread.set(Thread.currentThread());
                    resultWritten.countDown();
                    try {
                        if (!releaseResult.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("result delivery was not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("result delivery interrupted", exception);
                    }
                }
                super.write(bytes, offset, length);
            }
        };
        try (ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(
                task -> new Thread(task, "terminal-handler-test"))) {
            InteractiveTerminal terminal = terminal(dispatcher, executor, output, tree());
            ChunkThenBlockingInput input = new ChunkThenBlockingInput("work\r");
            Thread reader = Thread.ofVirtual().start(() -> terminal.run(input));

            assertThat(resultWritten.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch executorAvailable = new CountDownLatch(1);
            executor.submit(executorAvailable::countDown);
            assertThat(executorAvailable.await(5, TimeUnit.SECONDS)).isTrue();
            releaseResult.countDown();
            terminal.close();
            reader.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(handlerThread.get().isVirtual()).isFalse();
            assertThat(handlerThread.get().getName()).isEqualTo("terminal-handler-test");
            assertThat(resultThread.get().isVirtual()).isTrue();
            assertThat(resultThread.get().getName()).startsWith("orion-terminal-completion-");
        }
    }

    @Test
    void completingCommandCoalescesCtrlCAndIgnoredInputToOneBellPerChunk() throws Exception {
        CountDownLatch resultWriting = new CountDownLatch(1);
        CountDownLatch releaseResult = new CountDownLatch(1);
        CountDownLatch promptAfterResult = new CountDownLatch(1);
        CountDownLatch ignoredBell = new CountDownLatch(1);
        BlockingResultOutput output = new BlockingResultOutput(
                resultWriting,
                releaseResult,
                promptAfterResult,
                ignoredBell);
        try (ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            ChunkQueueInput input = new ChunkQueueInput();
            InteractiveTerminal terminal = terminal(
                    request -> new CommandResult.Message("first"),
                    executor,
                    output,
                    tree());
            Thread reader = Thread.ofVirtual().start(() -> terminal.run(input));
            input.send("command\r".getBytes(StandardCharsets.UTF_8));
            assertThat(resultWriting.await(5, TimeUnit.SECONDS)).isTrue();

            input.send(new byte[]{3, 'x'});
            releaseResult.countDown();
            assertThat(promptAfterResult.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ignoredBell.await(5, TimeUnit.SECONDS)).isTrue();
            input.send("quit\r".getBytes(StandardCharsets.UTF_8));
            reader.join(TimeUnit.SECONDS.toMillis(5));

            assertThat(reader.isAlive()).isFalse();
            assertThat(output.bells.get()).isEqualTo(1);
        }
    }

    @Test
    void closesAnIdleBulkReaderAndConvertsOutputFailureToExitOne() throws Exception {
        BlockingBulkInput input = new BlockingBulkInput();
        InteractiveTerminal terminal = terminal(
                ignored -> new CommandResult.Message("unused"),
                directExecutor(),
                OutputStream.nullOutputStream(),
                tree());
        Thread reader = Thread.ofVirtual().start(() -> terminal.run(input));
        assertThat(input.reading.await(5, TimeUnit.SECONDS)).isTrue();

        terminal.close();
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(reader.isAlive()).isFalse();
        assertThat(input.bulkRead.get()).isTrue();

        InteractiveTerminal failed = terminal(
                ignored -> new CommandResult.Message("unused"),
                directExecutor(),
                new FailingOutputStream(),
                tree());
        assertThat(failed.run(input(""))).isEqualTo(1);
    }

    @Test
    void rendersSanitizedUnavailableAndFailedPathOnlyNavigation() {
        RuntimeException cause = new RuntimeException("sensitive failure");

        assertPathNavigationFailure(
                new ScopedResourceCatalogResult.Unavailable<>("sensitive source"),
                "SERVICE_UNAVAILABLE: Resource service is unavailable",
                "sensitive source");
        assertPathNavigationFailure(
                new ScopedResourceCatalogResult.Failed<>("sensitive source", cause),
                "HANDLER_FAILED: Resource lookup failed",
                "sensitive failure");
    }

    private static void assertPathNavigationFailure(
            ScopedResourceCatalogResult<String> result,
            String expected,
            String sensitive) {
        CommandNode root = CommandNode.builder()
                .child("repository", CommandNode.builder()
                        .dynamicChild(
                                new ScopedResourceResolver<>((ignored, parents) -> result, true),
                                CommandNode.builder().build())
                        .build())
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean dispatched = new AtomicBoolean();
        InteractiveTerminal terminal = terminal(request -> {
            dispatched.set(true);
            return new CommandResult.Message("unexpected");
        }, directExecutor(), output, root);

        assertThat(terminal.run(input("/repository/item\r\u0004"))).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains(expected).doesNotContain(sensitive);
        assertThat(dispatched).isFalse();
    }

    private static InteractiveTerminal terminal(
            CommandDispatcher dispatcher,
            ExecutorService executor,
            OutputStream output,
            CommandNode root) {
        InteractiveTerminal.Connection connection = new InteractiveTerminal.Connection(
                SecurityContext.createContext(),
                "alice",
                "request-1",
                "session-1",
                "127.0.0.1",
                Map.of("transport", "ssh-shell"));
        return new InteractiveTerminal(
                dispatcher,
                new CommandNavigator(root),
                executor,
                connection,
                output,
                false,
                80);
    }

    private static CommandNode tree() {
        CommandDefinition show = new CommandDefinition(
                "show",
                0,
                0,
                Set.of(),
                Set.of(),
                ignored -> true,
                ignored -> AccessDecision.allow("test"),
                ignored -> new CommandResult.Message("shown"),
                CommandCompletion.none(),
                CommandQuery.none());
        return CommandNode.builder()
                .child("session", CommandNode.builder().action(show).build())
                .build();
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return shutdown;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    private static final class BlockingBulkInput extends java.io.InputStream {
        private final CountDownLatch reading = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean bulkRead = new AtomicBoolean();

        @Override
        public int read() {
            throw new AssertionError("single-byte read must never be called");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            bulkRead.set(true);
            reading.countDown();
            try {
                if (!closed.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("reader did not close");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("reader interrupted", exception);
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ChunkThenBlockingInput extends java.io.InputStream {
        private final byte[] chunk;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();

        private ChunkThenBlockingInput(String chunk) {
            this.chunk = chunk.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            throw new AssertionError("single-byte read must never be called");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (delivered.compareAndSet(false, true)) {
                System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                return chunk.length;
            }
            try {
                closed.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", exception);
            }
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ChunkQueueInput extends java.io.InputStream {
        private static final byte[] EOF = new byte[0];
        private final java.util.concurrent.BlockingQueue<byte[]> chunks =
                new java.util.concurrent.LinkedBlockingQueue<>();

        @Override
        public int read() {
            throw new AssertionError("single-byte read must never be called");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                byte[] chunk = chunks.take();
                if (chunk == EOF) {
                    return -1;
                }
                System.arraycopy(chunk, 0, bytes, offset, chunk.length);
                return chunk.length;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", exception);
            }
        }

        private void send(byte[] chunk) {
            chunks.add(chunk);
        }

        @Override
        public void close() {
            chunks.add(EOF);
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("delivery failed");
        }
    }

    private static final class BlockingResultOutput extends ByteArrayOutputStream {
        private final CountDownLatch resultWriting;
        private final CountDownLatch releaseResult;
        private final CountDownLatch followingPrompt;
        private final CountDownLatch ignoredInputBell;
        private final AtomicBoolean resultWritten = new AtomicBoolean();
        private final AtomicInteger bells = new AtomicInteger();

        private BlockingResultOutput(
                CountDownLatch resultWriting,
                CountDownLatch releaseResult,
                CountDownLatch followingPrompt,
                CountDownLatch ignoredInputBell) {
            this.resultWriting = resultWriting;
            this.releaseResult = releaseResult;
            this.followingPrompt = followingPrompt;
            this.ignoredInputBell = ignoredInputBell;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            String value = new String(bytes, offset, length, StandardCharsets.UTF_8);
            if (value.equals("first\n")) {
                resultWriting.countDown();
                try {
                    if (!releaseResult.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("result write was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("result write interrupted", exception);
                }
                resultWritten.set(true);
            } else if (resultWritten.get() && value.contains("[alice@orion] > ")) {
                followingPrompt.countDown();
            } else if (value.equals("\u0007")) {
                bells.incrementAndGet();
                ignoredInputBell.countDown();
            }
            super.write(bytes, offset, length);
        }
    }
}
