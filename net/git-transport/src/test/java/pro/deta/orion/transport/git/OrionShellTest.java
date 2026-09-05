package pro.deta.orion.transport.git;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.channel.ChannelAsyncInputStream;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.IoWriteFutureImpl;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.io.IoInputStream;
import org.apache.sshd.common.io.IoOutputStream;
import org.apache.sshd.common.io.IoReadFuture;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.closeable.AbstractCloseable;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.AsyncCommandStreamsAware;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SshConnectionCredentials;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNavigator;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandQuery;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.transport.git.auth.RootSshKeyEnrollmentSession;
import pro.deta.orion.transport.git.auth.OrionSshAuthenticator;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.transport.git.command.ReadOnlyDomainCommandCatalog;
import pro.deta.orion.transport.git.command.read.DefaultOperatorDomainSource;
import pro.deta.orion.transport.git.command.read.OperatorDomainViews;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Timeout(10)
class OrionShellTest {
    private final OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void readsOnlyBulkOnOneVirtualThreadAndAppliesPtyResize() throws Exception {
        BlockingQueue<CommandRequest> requests = new LinkedBlockingQueue<>();
        CommandDispatcher dispatcher = request -> {
            requests.add(request);
            return new CommandResult.Message("ok");
        };
        OrionShell shell = shell(dispatcher);
        TestChannelSession channel = channel();
        MutableEnvironment environment = new MutableEnvironment("xterm-256color", "40");
        AsyncInput input = new AsyncInput(new ArrayList<>());
        ControllableOutput output = new ControllableOutput(true);
        ControllableOutput error = new ControllableOutput(true);
        ExitRecorder exit = new ExitRecorder();
        Command command = configuredAsync(shell.createShell(channel), input, output, error, exit);

        command.start(channel, environment);

        assertThat(input.reading.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(input.reader.get().isVirtual()).isTrue();
        assertThat(input.reader.get().getName()).startsWith("orion-ssh-terminal-");
        input.send("probe\r");
        CommandRequest first = requests.poll(2, TimeUnit.SECONDS);
        assertThat(first.context().presentation().interactive()).isTrue();
        assertThat(first.context().presentation().ansi()).isTrue();
        assertThat(first.context().presentation().terminalColumns()).isEqualTo(40);
        assertThat(first.context().securityContext().getUserIdentity().getUserId()).isEqualTo("operator");
        SshConnectionCredentials credentialFacts =
                first.context().securityContext().getSshConnectionCredentials();
        assertThat(credentialFacts.authenticatedKeyFingerprint()).contains("SHA256:current");
        assertThat(credentialFacts.candidatePublicKeys()).hasSize(1);
        assertThat(first.context().auditMetadata().toString())
                .doesNotContain(credentialFacts.candidatePublicKeys().getFirst());
        assertThat(output.commandCompleted.await(2, TimeUnit.SECONDS)).isTrue();

        environment.values.put(Environment.ENV_COLUMNS, "120");
        environment.signal(channel, Signal.WINCH);
        input.send("probe\r");
        CommandRequest second = requests.poll(2, TimeUnit.SECONDS);
        assertThat(second.context().presentation().terminalColumns()).isEqualTo(120);
        assertThat(output.secondCommandCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        input.send("quit\r");

        assertThat(exit.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(exit.code.get()).isZero();
        assertThat(exit.calls.get()).isEqualTo(1);
        assertThat(output.value()).contains("\u001b[2K");
        assertThat(environment.removes.get()).isEqualTo(1);
    }

    @Test
    void interactiveShellDispatchesTheReadOnlyWhoamiCommand() throws Exception {
        DefaultOperatorDomainSource source = new DefaultOperatorDomainSource(
                new InMemoryNativeGitRepositoryProvider(),
                new AggregateStateMachine(StateMachineDefinition.define().name("runtime").build()),
                () -> new OperatorDomainViews.SystemResourceView(1, 0, 0, 0));
        CommandNode tree = new ReadOnlyDomainCommandCatalog(source).commandTree();
        DefaultCommandDispatcher dispatcher = new DefaultCommandDispatcher(
                new CommandLineParser(), tree, new pro.deta.orion.command.CommandRowQuery());
        OrionShell shell = shell(dispatcher, new CommandNavigator(tree));
        TestChannelSession channel = channel();
        AsyncInput input = new AsyncInput(new ArrayList<>());
        ControllableOutput output = new ControllableOutput(true);
        ExitRecorder exit = new ExitRecorder();
        Command command = configuredAsync(
                shell.createShell(channel),
                input,
                output,
                new ControllableOutput(true),
                exit);

        command.start(channel, new MutableEnvironment("xterm", "80"));
        assertThat(input.reading.await(2, TimeUnit.SECONDS)).isTrue();
        input.send("whoami\r");
        awaitOutputContains(output, "userId=operator");
        input.send("quit\r");

        assertThat(exit.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(output.value()).contains("userId=operator", "[operator@orion] > ");
    }

    @Test
    void interactiveShellPreservesExplicitJsonAndTersePayloads() throws Exception {
        CommandDispatcher dispatcher = queryDispatcher();
        OrionShell shell = shell(dispatcher);
        TestChannelSession channel = channel();
        AsyncInput input = new AsyncInput(new ArrayList<>());
        ControllableOutput output = new ControllableOutput(true);
        ExitRecorder exit = new ExitRecorder();
        Command command = configuredAsync(
                shell.createShell(channel),
                input,
                output,
                new ControllableOutput(true),
                exit);

        command.start(channel, new MutableEnvironment("xterm", "80"));
        assertThat(input.reading.await(2, TimeUnit.SECONDS)).isTrue();
        input.send("/repository ls columns=id,refCount format=json\r");
        String json = "{\"columns\":[\"id\",\"refCount\"],"
                + "\"rows\":[{\"id\":\"project\",\"refCount\":0}],"
                + "\"page\":{\"number\":1,\"size\":100,\"matched\":1,\"next\":null}}\n";
        awaitOutputContains(output, json);
        input.send("/repository ls columns=id,refCount format=terse\r");
        awaitOutputContains(output, "project\t0\n");
        input.send("quit\r");

        assertThat(exit.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(output.value()).contains(json, "project\t0\n");
    }

    @Test
    void interactiveShellEscapesHostileStructuredFields() throws Exception {
        CommandDispatcher dispatcher = ignored -> CommandResult.Rows.unqueried(
                List.of(CommandColumn.text("repositoryName")),
                List.of(List.of(CommandValue.text("evil\r\n\t\u001b\\name"))));
        OrionShell shell = shell(dispatcher);
        TestChannelSession channel = channel();
        AsyncInput input = new AsyncInput(new ArrayList<>());
        ControllableOutput output = new ControllableOutput(true);
        ExitRecorder exit = new ExitRecorder();
        Command command = configuredAsync(
                shell.createShell(channel),
                input,
                output,
                new ControllableOutput(true),
                exit);

        command.start(channel, new MutableEnvironment("dumb", "80"));
        assertThat(input.reading.await(2, TimeUnit.SECONDS)).isTrue();
        input.send("/repository/evil show\r");
        awaitOutputContains(output, "evil\\r\\n\\t\\u001B\\\\name");
        input.send("quit\r");

        assertThat(exit.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(output.value()).doesNotContain("evil\r\n", "\u001b\\name");
    }

    private static void awaitOutputContains(ControllableOutput output, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!output.value().contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(output.value()).contains(expected);
    }

    @Test
    void destroyInterruptsActiveWorkClosesStreamsInOrderAndCompletesOnce() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch handlerInterrupted = new CountDownLatch(1);
        CommandDispatcher dispatcher = request -> {
            handlerStarted.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            } catch (InterruptedException exception) {
                handlerInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new CommandResult.Message("late");
        };
        List<String> closes = new ArrayList<>();
        OrionShell shell = shell(dispatcher);
        TestChannelSession channel = channel();
        MutableEnvironment environment = new MutableEnvironment("xterm", "80");
        AsyncInput input = new AsyncInput(closes);
        ControllableOutput output = new ControllableOutput(true, "output", closes);
        ControllableOutput error = new ControllableOutput(true, "error", closes);
        ExitRecorder exit = new ExitRecorder();
        Command command = configuredAsync(shell.createShell(channel), input, output, error, exit);
        command.start(channel, environment);
        input.send("probe\r");
        assertThat(handlerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        command.destroy(channel);
        input.eof();

        assertThat(handlerInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(exit.completed.await(2, TimeUnit.SECONDS)).isTrue();
        input.reader.get().join(TimeUnit.SECONDS.toMillis(2));
        assertThat(input.reader.get().isAlive()).isFalse();
        assertThat(exit.calls.get()).isEqualTo(1);
        assertThat(environment.removes.get()).isEqualTo(1);
        assertThat(closes).startsWith("input", "output").contains("error");
        assertThat(output.value()).doesNotContain("late");
    }

    @Test
    void promptUsesMinaAsyncStreamsWithoutTouchingSynchronousChannelOutput() throws Exception {
        OrionShell shell = shell(ignored -> new CommandResult.Message("unused"));
        TestChannelSession channel = channel();
        Command command = shell.createShell(channel);
        assertThat(command).isInstanceOf(AsyncCommandStreamsAware.class);
        AsyncCommandStreamsAware async = (AsyncCommandStreamsAware) command;
        ImmediateEofInput input = new ImmediateEofInput();
        ControllableOutput output = new ControllableOutput(true);
        ControllableOutput error = new ControllableOutput(true);
        ExitRecorder exit = new ExitRecorder();
        command.setInputStream(new FailingSynchronousInput());
        command.setOutputStream(new FailingSynchronousStream());
        command.setErrorStream(new FailingSynchronousStream());
        command.setExitCallback(exit);
        async.setIoInputStream(input);
        async.setIoOutputStream(output);
        async.setIoErrorStream(error);

        command.start(channel, new MutableEnvironment("xterm", "80"));

        assertThat(exit.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(output.writes).anySatisfy(write -> {
            assertThat(write.value()).contains("[operator@orion] > ");
            assertThat(write.thread().isVirtual()).isTrue();
        });
    }

    @Test
    void destroyAbortsPendingAsyncOutputWithoutWaitingForItsSerializationLock() throws Exception {
        OrionShell shell = shell(ignored -> new CommandResult.Message("unused"));
        TestChannelSession channel = channel();
        Command command = shell.createShell(channel);
        AsyncCommandStreamsAware async = (AsyncCommandStreamsAware) command;
        ImmediateEofInput input = new ImmediateEofInput();
        ControllableOutput output = new ControllableOutput(false);
        ControllableOutput error = new ControllableOutput(true);
        ExitRecorder exit = new ExitRecorder();
        command.setExitCallback(exit);
        async.setIoInputStream(input);
        async.setIoOutputStream(output);
        async.setIoErrorStream(error);
        command.start(channel, new MutableEnvironment("xterm", "80"));
        assertThat(output.writeStarted.await(2, TimeUnit.SECONDS)).isTrue();

        command.destroy(channel);

        assertThat(output.closedImmediately.get()).isTrue();
        assertThat(exit.completed.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void destroyClosesAllAsyncStreamsBeforeStartAndAfterStartFailure() throws Exception {
        List<String> beforeStartCloses = new ArrayList<>();
        Command beforeStart = shell(ignored -> new CommandResult.Message("unused")).createShell(channel());
        ExitRecorder beforeStartExit = new ExitRecorder();
        configuredAsync(
                beforeStart,
                new AsyncInput(beforeStartCloses),
                new ControllableOutput(true, "output", beforeStartCloses),
                new ControllableOutput(true, "error", beforeStartCloses),
                beforeStartExit);

        beforeStart.destroy(channel());

        assertThat(beforeStartCloses).containsExactly("input", "output", "error");
        assertThat(beforeStartExit.calls.get()).isEqualTo(1);

        List<String> failedStartCloses = new ArrayList<>();
        Command failedStart = shell(ignored -> new CommandResult.Message("unused")).createShell(channel());
        ExitRecorder failedStartExit = new ExitRecorder();
        failedStart.setExitCallback(failedStartExit);
        ((AsyncCommandStreamsAware) failedStart).setIoInputStream(new AsyncInput(failedStartCloses));
        assertThatThrownBy(() -> failedStart.start(channel(), new MutableEnvironment("xterm", "80")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("output");

        failedStart.destroy(channel());

        assertThat(failedStartCloses).containsExactly("input");
        assertThat(failedStartExit.calls.get()).isEqualTo(1);
    }

    @Test
    void recoverySessionIsRejectedBeforeInteractiveTerminalConstruction() throws Exception {
        TestChannelSession channel = channel();
        RootSshKeyEnrollmentSession.begin(
                channel.getSession(),
                "generation-1",
                List.of("ssh-rsa candidate"));

        Command command = shell(ignored -> new CommandResult.Message("wrong route")).createShell(channel);

        assertThat(command).isNotInstanceOf(AsyncCommandStreamsAware.class);
    }

    private OrionShell shell(CommandDispatcher dispatcher) {
        return shell(dispatcher, new CommandNavigator(CommandNode.builder().build()));
    }

    private static DefaultCommandDispatcher queryDispatcher() {
        CommandDefinition list = new CommandDefinition(
                "ls",
                0,
                0,
                Set.of(),
                Set.of(),
                ignored -> true,
                ignored -> pro.deta.orion.auth.check.AccessDecision.allow("test"),
                ignored -> CommandResult.Rows.unqueried(
                        List.of(CommandColumn.text("id"), CommandColumn.number("refCount")),
                        List.of(List.of(CommandValue.text("project"), CommandValue.number(0)))),
                CommandCompletion.none(),
                CommandQuery.enabled(List.of("id", "refCount"), Map.of()));
        CommandNode tree = CommandNode.builder()
                .child("repository", CommandNode.builder().action(list).build())
                .build();
        return new DefaultCommandDispatcher(
                new CommandLineParser(), tree, new pro.deta.orion.command.CommandRowQuery());
    }

    private OrionShell shell(CommandDispatcher dispatcher, CommandNavigator navigator) {
        return new OrionShell(
                dispatcher,
                navigator,
                executor);
    }

    private static Command configuredAsync(
            Command command,
            IoInputStream input,
            IoOutputStream output,
            IoOutputStream error,
            ExitRecorder exit) {
        command.setExitCallback(exit);
        AsyncCommandStreamsAware async = (AsyncCommandStreamsAware) command;
        async.setIoInputStream(input);
        async.setIoOutputStream(output);
        async.setIoErrorStream(error);
        return command;
    }

    private static TestChannelSession channel() {
        Map<AttributeRepository.AttributeKey<?>, Object> attributes = new HashMap<>();
        attributes.put(SSH_AUTHENTICATED_USER, new InternalUserImpl("operator", List.of()));
        ServerSession session = (ServerSession) Proxy.newProxyInstance(
                ServerSession.class.getClassLoader(),
                new Class<?>[]{ServerSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get(args[0]);
                    case "setAttribute" -> attributes.put(
                            (AttributeRepository.AttributeKey<?>) args[0], args[1]);
                    case "removeAttribute" -> attributes.remove(args[0]);
                    case "getRemoteAddress" -> new InetSocketAddress("192.0.2.10", 2222);
                    case "getUsername" -> "operator";
                    case "toString" -> "test-session";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        installCredentialFacts(session);
        return new TestChannelSession(session);
    }

    private static void installCredentialFacts(ServerSession session) {
        try {
            var currentField = OrionSshAuthenticator.class.getDeclaredField("AUTHENTICATED_KEY_FINGERPRINT");
            currentField.setAccessible(true);
            @SuppressWarnings("unchecked")
            AttributeRepository.AttributeKey<String> current =
                    (AttributeRepository.AttributeKey<String>) currentField.get(null);
            var candidatesField = OrionSshAuthenticator.class.getDeclaredField("PROVED_PUBLIC_KEYS");
            candidatesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            AttributeRepository.AttributeKey<LinkedHashMap<String, PublicKey>> candidates =
                    (AttributeRepository.AttributeKey<LinkedHashMap<String, PublicKey>>) candidatesField.get(null);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            PublicKey candidate = generator.generateKeyPair().getPublic();
            session.setAttribute(current, "SHA256:current");
            session.setAttribute(candidates, new LinkedHashMap<>(Map.of("candidate", candidate)));
        } catch (ReflectiveOperationException | java.security.GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class || type == short.class || type == int.class || type == long.class) {
            return 0;
        }
        if (type == float.class || type == double.class) {
            return 0.0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class TestChannelSession extends ChannelSession {
        private final ServerSession session;

        private TestChannelSession(ServerSession session) {
            this.session = session;
        }

        @Override
        public ServerSession getSession() {
            return session;
        }
    }

    private static final class MutableEnvironment implements Environment {
        private final Map<String, String> values = new HashMap<>();
        private final List<SignalListener> listeners = new ArrayList<>();
        private final AtomicInteger removes = new AtomicInteger();

        private MutableEnvironment(String term, String columns) {
            values.put(ENV_TERM, term);
            values.put(ENV_COLUMNS, columns);
        }

        @Override
        public Map<String, String> getEnv() {
            return values;
        }

        @Override
        public Map<PtyMode, Integer> getPtyModes() {
            return Map.of();
        }

        @Override
        public void addSignalListener(SignalListener listener, Collection<Signal> signals) {
            listeners.add(listener);
        }

        @Override
        public void removeSignalListener(SignalListener listener) {
            if (listeners.remove(listener)) {
                removes.incrementAndGet();
            }
        }

        private void signal(Channel channel, Signal signal) {
            for (SignalListener listener : List.copyOf(listeners)) {
                listener.signal(channel, signal);
            }
        }
    }

    private static final class AsyncInput extends AbstractCloseable implements IoInputStream {
        private static final byte[] EOF = new byte[0];
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private final List<String> closes;
        private final CountDownLatch reading = new CountDownLatch(1);
        private final AtomicReference<Thread> reader = new AtomicReference<>();
        private final ReentrantLock lock = new ReentrantLock();
        private PendingRead pending;

        private AsyncInput(List<String> closes) {
            this.closes = closes;
        }

        @Override
        public IoReadFuture read(Buffer buffer) {
            reader.compareAndSet(null, Thread.currentThread());
            reading.countDown();
            ChannelAsyncInputStream.IoReadFutureImpl future =
                    new ChannelAsyncInputStream.IoReadFutureImpl("test-read", buffer);
            lock.lock();
            try {
                byte[] chunk = chunks.poll();
                if (chunk == null) {
                    pending = new PendingRead(buffer, future);
                } else {
                    complete(buffer, future, chunk);
                }
            } finally {
                lock.unlock();
            }
            return future;
        }

        private void send(String value) {
            send(value.getBytes(StandardCharsets.UTF_8));
        }

        private void eof() {
            send(EOF);
        }

        private void send(byte[] chunk) {
            lock.lock();
            try {
                if (pending == null) {
                    chunks.add(chunk);
                } else {
                    PendingRead current = pending;
                    pending = null;
                    complete(current.buffer(), current.future(), chunk);
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        protected void doCloseImmediately() {
            closes.add("input");
            eof();
            super.doCloseImmediately();
        }

        private static void complete(
                Buffer buffer,
                ChannelAsyncInputStream.IoReadFutureImpl future,
                byte[] chunk) {
            if (chunk == EOF) {
                future.setValue(new EOFException("eof"));
            } else {
                buffer.putRawBytes(chunk);
                future.setValue(chunk.length);
            }
        }

        private record PendingRead(Buffer buffer, ChannelAsyncInputStream.IoReadFutureImpl future) {}
    }

    private static final class ExitRecorder implements ExitCallback {
        private final AtomicInteger code = new AtomicInteger(-1);
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onExit(int exitValue, String exitMessage, boolean closeImmediately) {
            code.set(exitValue);
            calls.incrementAndGet();
            completed.countDown();
        }
    }

    private static final class FailingSynchronousStream extends OutputStream {
        @Override
        public void write(int value) {
            throw new AssertionError("synchronous Mina stream must not be used");
        }
    }

    private static final class FailingSynchronousInput extends InputStream {
        @Override
        public int read() {
            throw new AssertionError("synchronous Mina stream must not be used");
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            throw new AssertionError("synchronous Mina stream must not be used");
        }
    }

    private static final class ImmediateEofInput extends AbstractCloseable implements IoInputStream {
        @Override
        public IoReadFuture read(Buffer buffer) {
            ChannelAsyncInputStream.IoReadFutureImpl future =
                    new ChannelAsyncInputStream.IoReadFutureImpl("test-read", buffer);
            future.setValue(new EOFException("eof"));
            return future;
        }
    }

    private static final class ControllableOutput extends AbstractCloseable implements IoOutputStream {
        private final boolean completeWrites;
        private final String name;
        private final List<String> closes;
        private final List<AsyncWrite> writes = new ArrayList<>();
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch commandCompleted = new CountDownLatch(1);
        private final CountDownLatch secondCommandCompleted = new CountDownLatch(1);
        private final AtomicBoolean closedImmediately = new AtomicBoolean();
        private final AtomicBoolean resultWritten = new AtomicBoolean();
        private final AtomicInteger completedCommands = new AtomicInteger();
        private final AtomicReference<IoWriteFutureImpl> pending = new AtomicReference<>();

        private ControllableOutput(boolean completeWrites) {
            this(completeWrites, null, List.of());
        }

        private ControllableOutput(boolean completeWrites, String name, List<String> closes) {
            this.completeWrites = completeWrites;
            this.name = name;
            this.closes = closes;
        }

        @Override
        public IoWriteFuture writeBuffer(Buffer buffer) {
            byte[] bytes = new byte[buffer.available()];
            buffer.getRawBytes(bytes);
            String value = new String(bytes, StandardCharsets.UTF_8);
            writes.add(new AsyncWrite(value, Thread.currentThread()));
            if (value.equals("ok\n")) {
                resultWritten.set(true);
            } else if (value.contains("[operator@orion] > ") && resultWritten.compareAndSet(true, false)) {
                commandCompleted.countDown();
                if (completedCommands.incrementAndGet() == 2) {
                    secondCommandCompleted.countDown();
                }
            }
            IoWriteFutureImpl future = new IoWriteFutureImpl("test-write", buffer);
            pending.set(future);
            writeStarted.countDown();
            if (completeWrites) {
                future.setValue(Boolean.TRUE);
            }
            return future;
        }

        @Override
        protected void doCloseImmediately() {
            closedImmediately.set(true);
            if (name != null) {
                closes.add(name);
            }
            IoWriteFutureImpl future = pending.getAndSet(null);
            if (future != null) {
                future.setValue(new EOFException("closed"));
            }
            super.doCloseImmediately();
        }

        private String value() {
            StringBuilder value = new StringBuilder();
            for (AsyncWrite write : writes) {
                value.append(write.value());
            }
            return value.toString();
        }
    }

    private record AsyncWrite(String value, Thread thread) {}
}
