package pro.deta.orion.transport.git.ssh;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SshConnectionCredentials;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.SshKeyEnrollmentResult;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandQuery;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.render.PlainCommandRenderer;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.transport.git.command.ReadOnlyDomainCommandCatalog;
import pro.deta.orion.transport.git.command.read.DefaultOperatorDomainSource;
import pro.deta.orion.transport.git.command.read.OperatorDomainViews;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.transport.git.auth.RootSshKeyEnrollmentSession;
import pro.deta.orion.transport.git.auth.OrionSshAuthenticator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Timeout(5)
class SshCommandFactoryTest {
    private final List<OrionExecutor> executors = new java.util.ArrayList<>();

    @AfterEach
    void stopExecutor() {
        for (OrionExecutor executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test
    void nonGitExecDispatchesAuthenticatedMetadataAndDeliversRenderedOutput() throws Exception {
        AtomicReference<CommandRequest> recorded = new AtomicReference<>();
        CommandDispatcher dispatcher = request -> {
            recorded.set(request);
            return new CommandResult.Exit(7, "stopped");
        };
        TestChannelSession channel = channel(true);
        TrackingOutputStream output = new TrackingOutputStream();
        TrackingOutputStream error = new TrackingOutputStream();

        ExitOutcome exit = run(factory(dispatcher), channel, "whoami", output, error);

        assertEquals(7, exit.code());
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertEquals("stopped\n", error.toString(StandardCharsets.UTF_8));
        assertEquals(1, output.flushes());
        assertEquals(1, error.flushes());
        CommandRequest request = recorded.get();
        assertEquals("operator", request.context().securityContext().getUserIdentity().getUserId());
        assertNotEquals(request.context().requestId(), request.context().sessionId());
        assertEquals("test-session", request.context().sessionId());
        assertEquals("/192.0.2.10:2222", request.context().sourceAddress());
        assertEquals("/", request.context().currentPath().toString());
        assertEquals(pro.deta.orion.command.CommandPresentation.plain(), request.context().presentation());
        SshConnectionCredentials credentialFacts =
                request.context().securityContext().getSshConnectionCredentials();
        assertEquals(java.util.Optional.of("SHA256:current"), credentialFacts.authenticatedKeyFingerprint());
        assertEquals(1, credentialFacts.candidatePublicKeys().size());
        assertFalse(request.context().auditMetadata().toString()
                .contains(credentialFacts.candidatePublicKeys().getFirst()));
        assertEquals(Map.of("transport", "ssh", "requestType", "exec"), request.context().auditMetadata());
    }

    @Test
    void readOnlyDomainExecRendersPlainOutputWithoutPromptOrAnsi() throws Exception {
        DefaultOperatorDomainSource source = new DefaultOperatorDomainSource(
                new InMemoryNativeGitRepositoryProvider(),
                new AggregateStateMachine(StateMachineDefinition.define().name("runtime").build()),
                () -> new OperatorDomainViews.SystemResourceView(1, 0, 0, 0));
        DefaultCommandDispatcher dispatcher = new DefaultCommandDispatcher(
                new CommandLineParser(),
                new ReadOnlyDomainCommandCatalog(source).commandTree(),
                new pro.deta.orion.command.CommandRowQuery());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        ExitOutcome exit = run(factory(dispatcher), channel(true), "whoami", output, error);

        assertEquals(0, exit.code());
        assertEquals("userId=operator\n", output.toString(StandardCharsets.UTF_8));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("\u001b["));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("@orion] >"));
    }

    @Test
    void execRendersExplicitJsonAndTerseAndRejectsNoPtyTable() throws Exception {
        CommandDispatcher dispatcher = queryDispatcher();

        ByteArrayOutputStream json = new ByteArrayOutputStream();
        ExitOutcome jsonExit = run(
                factory(dispatcher), channel(true),
                "/repository ls columns=id,refCount format=json", json, new ByteArrayOutputStream());
        ByteArrayOutputStream terse = new ByteArrayOutputStream();
        ExitOutcome terseExit = run(
                factory(dispatcher), channel(true),
                "/repository ls columns=id,refCount format=terse", terse, new ByteArrayOutputStream());
        ByteArrayOutputStream tableError = new ByteArrayOutputStream();
        ExitOutcome tableExit = run(
                factory(dispatcher), channel(true),
                "/repository ls format=table", new ByteArrayOutputStream(), tableError);

        assertEquals(0, jsonExit.code());
        assertEquals(
                "{\"columns\":[\"id\",\"refCount\"],\"rows\":[{\"id\":\"project\",\"refCount\":0}],"
                        + "\"page\":{\"number\":1,\"size\":100,\"matched\":1,\"next\":null}}\n",
                json.toString(StandardCharsets.UTF_8));
        assertEquals(0, terseExit.code());
        assertEquals("project\t0\n", terse.toString(StandardCharsets.UTF_8));
        assertEquals(2, tableExit.code());
        assertEquals(
                "INVALID_ARGUMENTS: Table format requires an interactive terminal\n",
                tableError.toString(StandardCharsets.UTF_8));
        assertFalse(json.toString(StandardCharsets.UTF_8).contains("\u001b"));
        assertFalse(terse.toString(StandardCharsets.UTF_8).contains("\u001b"));
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

    @Test
    void execEscapesHostileStructuredFields() throws Exception {
        CommandDispatcher dispatcher = ignored -> new CommandResult.ObjectValue(Map.of(
                "repositoryName", CommandValue.text("evil\r\n\t\u001b\\name")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        ExitOutcome exit = run(
                factory(dispatcher),
                channel(true),
                "/repository/evil show",
                output,
                new ByteArrayOutputStream());

        assertEquals(0, exit.code());
        assertEquals("repositoryName=evil\\r\\n\\t\\u001B\\\\name\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void missingIdentityIsDispatchedAsAnonymousContext() throws Exception {
        AtomicReference<CommandRequest> recorded = new AtomicReference<>();
        CommandDispatcher dispatcher = request -> {
            recorded.set(request);
            return new CommandResult.Failure(CommandFailureCode.ACCESS_DENIED, "Access denied", List.of());
        };

        ExitOutcome exit = run(
                factory(dispatcher),
                channel(false),
                "state",
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());

        assertEquals(10, exit.code());
        assertTrue(recorded.get().context().securityContext().getUserIdentity().isAnonymous());
    }

    @Test
    void dispatcherFailureOutputFailureAndExecutorRejectionHaveStableExitOne() throws Exception {
        CommandDispatcher throwing = request -> {
            throw new IllegalStateException("private detail");
        };
        ByteArrayOutputStream dispatcherError = new ByteArrayOutputStream();
        ExitOutcome dispatcherExit = run(
                factory(throwing),
                channel(true),
                "state",
                new ByteArrayOutputStream(),
                dispatcherError);
        assertEquals(1, dispatcherExit.code());
        assertEquals(
                "HANDLER_FAILED: Command handler failed\n",
                dispatcherError.toString(StandardCharsets.UTF_8));
        assertFalse(dispatcherError.toString(StandardCharsets.UTF_8).contains("private detail"));

        ExitOutcome outputExit = run(
                factory(request -> new CommandResult.Message("ok")),
                channel(true),
                "state",
                new FailingOutputStream(),
                new ByteArrayOutputStream());
        assertEquals(1, outputExit.code());

        SshCommandFactory rejectedFactory = factory(request -> new CommandResult.Message("unreachable"));
        executors.getLast().shutdownNow();
        ByteArrayOutputStream rejectionError = new ByteArrayOutputStream();
        ExitOutcome rejected = run(
                rejectedFactory,
                channel(true),
                "state",
                new ByteArrayOutputStream(),
                rejectionError);
        assertEquals(1, rejected.code());
        assertEquals("HANDLER_FAILED: Service unavailable\n", rejectionError.toString(StandardCharsets.UTF_8));
    }

    @Test
    void destroyCancelsAnActiveCommandAndCompletesOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        CommandDispatcher dispatcher = request -> {
            entered.countDown();
            while (!request.context().cancellation().isCancelled()) {
                Thread.onSpinWait();
            }
            awaitUninterruptibly(allowReturn);
            return new CommandResult.Failure(CommandFailureCode.CANCELLED, "Command was cancelled", List.of());
        };
        SshCommandFactory factory = factory(dispatcher);
        TestChannelSession channel = channel(true);
        CloseAwareOutputStream output = new CloseAwareOutputStream();
        CloseAwareOutputStream error = new CloseAwareOutputStream();
        Command command = configured(
                factory.createCommand(channel, "monitor"),
                output,
                error,
                new AtomicReference<>(),
                new CountDownLatch(1),
                new AtomicInteger());

        command.start(channel, null);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        command.destroy(channel);
        allowReturn.countDown();
        CloseOnDestroyCommand closeable = (CloseOnDestroyCommand) command;
        RecordingExitCallback callback = (RecordingExitCallback) closeable.getExitCallback();

        assertTrue(callback.completed.await(2, TimeUnit.SECONDS));
        assertEquals(125, callback.outcome.get().code());
        assertEquals(1, callback.calls.get());
        assertTrue(output.closed());
        assertTrue(error.closed());
        assertTrue(error.writeAttempted.await(2, TimeUnit.SECONDS));
        assertTrue(output.rejectedWrite() || error.rejectedWrite());
    }

    @Test
    void destroyWinsAConcurrentSuccessfulCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        CommandDispatcher dispatcher = request -> {
            entered.countDown();
            awaitUninterruptibly(allowReturn);
            return new CommandResult.Message("late success");
        };
        TestChannelSession channel = channel(true);
        CloseAwareOutputStream output = new CloseAwareOutputStream();
        Command command = configured(
                factory(dispatcher).createCommand(channel, "monitor"),
                output,
                new CloseAwareOutputStream(),
                new AtomicReference<>(),
                new CountDownLatch(1),
                new AtomicInteger());

        command.start(channel, null);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        command.destroy(channel);
        allowReturn.countDown();
        CloseOnDestroyCommand closeable = (CloseOnDestroyCommand) command;
        RecordingExitCallback callback = (RecordingExitCallback) closeable.getExitCallback();

        assertTrue(callback.completed.await(2, TimeUnit.SECONDS));
        assertEquals(125, callback.outcome.get().code());
        assertTrue(output.writeAttempted.await(2, TimeUnit.SECONDS));
        assertEquals(1, callback.calls.get());
    }

    @Test
    void gitWireCommandsNeverEnterTheOrionDispatcher() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        SshCommandFactory factory = factory(request -> {
            dispatches.incrementAndGet();
            return new CommandResult.Message("wrong route");
        });
        for (String commandLine : List.of(
                "git-upload-pack '/demo.git'",
                "git-receive-pack '/demo.git'")) {
            run(
                    factory,
                    channel(true),
                    commandLine,
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());
        }

        assertEquals(0, dispatches.get());
    }

    @Test
    void recoverySessionRejectsNormalCommandsAndCompletesExactEnrollmentCommand() throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger enrollments = new AtomicInteger();
        OrionAccessControlService accessControl = (OrionAccessControlService) Proxy.newProxyInstance(
                OrionAccessControlService.class.getClassLoader(),
                new Class<?>[]{OrionAccessControlService.class},
                (proxy, method, args) -> {
                    if ("completeRootSshKeyEnrollment".equals(method.getName())) {
                        enrollments.incrementAndGet();
                        assertEquals("generation-1", args[0]);
                        assertEquals(List.of("ssh-rsa candidate"), args[1]);
                        return SshKeyEnrollmentResult.success();
                    }
                    return defaultValue(method.getReturnType());
                });
        SshCommandFactory factory = factory(request -> {
            dispatches.incrementAndGet();
            return new CommandResult.Message("wrong route");
        }, accessControl);
        TestChannelSession channel = channel(true);
        RootSshKeyEnrollmentSession.begin(
                channel.getSession(),
                "generation-1",
                List.of("ssh-rsa candidate"));

        ExitOutcome rejected = run(
                factory,
                channel,
                "issue-token",
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExitOutcome enrolled = run(factory, channel, "enroll-key", output, new ByteArrayOutputStream());
        ExitOutcome sameConnectionTokenIssue = run(
                factory,
                channel,
                "issue-token 600",
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());

        assertEquals(1, rejected.code());
        assertEquals(0, enrolled.code());
        assertEquals(1, sameConnectionTokenIssue.code());
        assertEquals("Root SSH key enrolled. Reconnect with the enrolled key.\n", output.toString(StandardCharsets.UTF_8));
        assertEquals(0, dispatches.get());
        assertEquals(1, enrollments.get());
        assertFalse(RootSshKeyEnrollmentSession.isPending(channel.getSession()));
        assertTrue(RootSshKeyEnrollmentSession.isRestricted(channel.getSession()));
    }

    @Test
    void nativeInitialRequestDataParsesQuotedRepositoryAndProtocol() {
        InitialRequestData data = SshCommandFactory.initialRequestData(
                "git-upload-pack '/team/project.git'",
                environment(Map.of("GIT_PROTOCOL", "version=2")));

        assertEquals(InitialRequestService.UPLOAD_PACK, data.getService());
        assertEquals("team/project", data.getRepositoryPath());
        assertNull(data.getHost());
        assertEquals(Map.of("version", "2"), data.getParameters());
        assertEquals(
                InitialRequestData.ProtocolVersion.V2,
                data.getProtocolVersion().orElseThrow());
    }

    @Test
    void nativeInitialRequestDataRejectsPathTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SshCommandFactory.initialRequestData(
                        "git-upload-pack '/../outside.git'",
                        environment(Map.of())));
    }

    @Test
    void receivePackProtocolErrorWritesStackTraceToSidebandErrorChannel()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeException error = new RuntimeException("boom");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("Example", "method", "Example.java", 12)
        });

        SshCommandFactory.writeGitProtocolException(
                output,
                "git-receive-pack '/demo.git'",
                error);

        byte[] packet = output.toByteArray();
        assertEquals(3, packet[4]);
        String payload = new String(
                packet,
                5,
                packet.length - 5,
                StandardCharsets.UTF_8);
        assertTrue(payload.contains("java.lang.RuntimeException: boom"));
        assertTrue(payload.contains("at Example.method(Example.java:12)"));
    }

    @Test
    void protocolErrorHelpersDoNotCreateBlockingWireTransport() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java"));
        String helpers = source.substring(
                source.indexOf("static void writeGitProtocolException("),
                source.indexOf("private static boolean isReceivePack("));

        assertFalse(helpers.contains("new GitBlockingWireTransport("));
    }

    @Test
    void receivePackProtocolErrorSplitsLargeStackTrace() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimeException error = new RuntimeException("boom");
        StackTraceElement[] stackTrace = new StackTraceElement[2_000];
        for (int index = 0; index < stackTrace.length; index++) {
            stackTrace[index] = new StackTraceElement(
                    "ExampleClass" + index,
                    "method",
                    "Example.java",
                    index + 1);
        }
        error.setStackTrace(stackTrace);

        SshCommandFactory.writeGitProtocolException(
                output,
                "git-receive-pack '/demo.git'",
                error);

        byte[] bytes = output.toByteArray();
        int secondPacketOffset = 0xfff0;
        assertEquals("fff0", new String(
                bytes,
                0,
                4,
                StandardCharsets.US_ASCII));
        assertEquals(3, bytes[4]);
        assertEquals(3, bytes[secondPacketOffset + 4]);
    }

    private static Environment environment(Map<String, String> values) {
        return (Environment) Proxy.newProxyInstance(
                Environment.class.getClassLoader(),
                new Class<?>[]{Environment.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEnv" -> values;
                    case "toString" -> "Environment" + values;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            method.toString());
                });
    }

    private SshCommandFactory factory(CommandDispatcher dispatcher) {
        return factory(dispatcher, null);
    }

    private SshCommandFactory factory(
            CommandDispatcher dispatcher,
            OrionAccessControlService accessControlService) {
        OrionExecutor executor = new OrionExecutor(2, new OrionThreadFactory());
        executors.add(executor);
        return new SshCommandFactory(
                executor,
                dispatcher,
                new PlainCommandRenderer(),
                null,
                null,
                accessControlService);
    }

    private static ExitOutcome run(
            SshCommandFactory factory,
            TestChannelSession channel,
            String commandLine,
            OutputStream output,
            OutputStream error) throws Exception {
        AtomicReference<ExitOutcome> outcome = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Command command = configured(
                factory.createCommand(channel, commandLine),
                output,
                error,
                outcome,
                completed,
                calls);

        command.start(channel, null);
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        return outcome.get();
    }

    private static Command configured(
            Command command,
            OutputStream output,
            OutputStream error,
            AtomicReference<ExitOutcome> outcome,
            CountDownLatch completed,
            AtomicInteger calls) {
        command.setInputStream(InputStream.nullInputStream());
        command.setOutputStream(output);
        command.setErrorStream(error);
        command.setExitCallback(new RecordingExitCallback(outcome, completed, calls));
        return command;
    }

    private static TestChannelSession channel(boolean authenticated) {
        Map<AttributeRepository.AttributeKey<?>, Object> attributes = new HashMap<>();
        if (authenticated) {
            attributes.put(SSH_AUTHENTICATED_USER, new InternalUserImpl("operator", List.of()));
        }
        ServerSession session = (ServerSession) Proxy.newProxyInstance(
                ServerSession.class.getClassLoader(),
                new Class<?>[]{ServerSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get(args[0]);
                    case "setAttribute" -> attributes.put(
                            (AttributeRepository.AttributeKey<?>) args[0], args[1]);
                    case "removeAttribute" -> attributes.remove(args[0]);
                    case "getRemoteAddress" -> new InetSocketAddress("192.0.2.10", 2222);
                    case "toString" -> "test-session";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        if (authenticated) {
            installCredentialFacts(session);
        }
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

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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

    private record ExitOutcome(int code, String message, boolean closeImmediately) {}

    private static final class RecordingExitCallback implements org.apache.sshd.server.ExitCallback {
        private final AtomicReference<ExitOutcome> outcome;
        private final CountDownLatch completed;
        private final AtomicInteger calls;

        private RecordingExitCallback(
                AtomicReference<ExitOutcome> outcome,
                CountDownLatch completed,
                AtomicInteger calls) {
            this.outcome = outcome;
            this.completed = completed;
            this.calls = calls;
        }

        @Override
        public void onExit(int exitValue, String exitMessage, boolean closeImmediately) {
            calls.incrementAndGet();
            outcome.set(new ExitOutcome(exitValue, exitMessage, closeImmediately));
            completed.countDown();
        }
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("delivery failed");
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private int flushes;

        @Override
        public void flush() throws IOException {
            flushes++;
            super.flush();
        }

        private int flushes() {
            return flushes;
        }
    }

    private static final class CloseAwareOutputStream extends OutputStream {
        private final CountDownLatch writeAttempted = new CountDownLatch(1);
        private volatile boolean closed;
        private volatile boolean rejectedWrite;

        @Override
        public void write(int value) throws IOException {
            writeAttempted.countDown();
            if (closed) {
                rejectedWrite = true;
                throw new IOException("stream is closed");
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        private boolean closed() {
            return closed;
        }

        private boolean rejectedWrite() {
            return rejectedWrite;
        }
    }
}
