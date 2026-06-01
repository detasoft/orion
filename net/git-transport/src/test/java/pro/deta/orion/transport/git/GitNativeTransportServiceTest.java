package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.git.GitCommand;
import pro.deta.orion.git.GitInternalService;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.stream.StandardStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(10)
class GitNativeTransportServiceTest {
    private static final byte[] HANDLED = "handled\n".getBytes(StandardCharsets.UTF_8);

    private GitNativeTransportService service;
    private OrionExecutor executor;

    @AfterEach
    void stopService() throws Exception {
        if (service != null) {
            service.onStop();
        }
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void injectConstructorDependsOnGitTransportConfigOnly() {
        Constructor<?> injectConstructor = injectConstructor();

        assertEquals(GitTransportConfig.class, injectConstructor.getParameterTypes()[0]);
    }

    @Test
    void disabledTransportDoesNotBind() {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        service = newService(gitService, false, 5_000);

        service.onStart();

        assertNull(service.boundAddress());
        assertTrue(gitService.calls.isEmpty());
    }

    @Test
    void enabledTransportBindsListenerBeforeReturningFromStart() {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        service = newService(gitService, true, 5_000);

        service.onStart();

        assertNotNull(service.boundAddress());
        assertTrue(service.isRunning());
    }

    @Test
    void bindFailureIsReportedToCaller() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            GitTransportConfig config = new GitTransportConfig("127.0.0.1", occupied.getLocalPort());
            config.setEnabled(true);
            executor = new OrionExecutor(4, new OrionThreadFactory());
            service = new GitNativeTransportService(config, new RecordingGitInternalService(), executor, 5_000);

            assertThrows(RuntimeException.class, service::onStart);

            assertFalse(service.isRunning());
        }
    }

    @Test
    void acceptsLocalConnectionAndDispatchesToGitService() throws Exception {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        InetSocketAddress address = startService(gitService, 5_000);

        assertArrayEquals(HANDLED, request(address, new byte[0]));

        Call call = gitService.awaitCall();
        assertNotNull(call.securityContext());
        assertNotNull(call.requestId());
        assertFalse(call.requestId().isBlank());
        assertEquals(call.requestId(), call.securityContext().getRequestId());
        assertTrue(call.clientId().contains("127.0.0.1") || call.clientId().contains("localhost"));
    }

    @Test
    void eachConnectionGetsDistinctRequestId() throws Exception {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        InetSocketAddress address = startService(gitService, 5_000);

        assertArrayEquals(HANDLED, request(address, new byte[0]));
        assertArrayEquals(HANDLED, request(address, new byte[0]));

        Call first = gitService.awaitCall();
        Call second = gitService.awaitCall();
        assertNotEquals(first.requestId(), second.requestId());
    }

    @Test
    void failedConnectionDoesNotStopListener() throws Exception {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        gitService.failNextCall();
        InetSocketAddress address = startService(gitService, 5_000);

        assertArrayEquals(new byte[0], request(address, new byte[0]));
        assertArrayEquals(HANDLED, request(address, new byte[0]));

        assertEquals(2, gitService.awaitCalls(2).size());
    }

    @Test
    void malformedInitialCommandDoesNotStopListener() throws Exception {
        RecordingGitRepositoryProvider repositoryProvider = new RecordingGitRepositoryProvider();
        GitInternalService gitService = new GitInternalService(repositoryProvider, new OrionEventManager());
        InetSocketAddress address = startService(gitService, 5_000);

        byte[] malformedResponse = request(address, pktLine("not-a-git-command\0host=localhost\0"));
        assertArrayEquals(new byte[0], malformedResponse);
        assertNull(repositoryProvider.lastExistsRepository);
        assertNull(repositoryProvider.lastCreatedRepository);

        byte[] validResponse = request(address, pktLine("git-upload-pack /missing.git\0host=localhost\0"));

        assertArrayEquals(new byte[0], validResponse);
        assertEquals("missing", repositoryProvider.lastExistsRepository);
        assertNull(repositoryProvider.lastCreatedRepository);
    }

    @Test
    void idleClientIsClosedBySocketTimeout() throws Exception {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        gitService.readBeforeResponding();
        InetSocketAddress address = startService(gitService, 100);

        try (Socket socket = connect(address)) {
            socket.setSoTimeout(2_000);
            assertEquals(-1, socket.getInputStream().read());
        }

        assertInstanceOf(UncheckedIOException.class, gitService.awaitCall().failure());
    }

    @Test
    void shutdownClosesListenerAndRejectsNewConnections() throws Exception {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        InetSocketAddress address = startService(gitService, 5_000);

        service.onStop();

        awaitConnectionFailure(address);
        assertNull(service.boundAddress());
    }

    @Test
    void stoppingBeforeStartIsHarmless() {
        service = newService(new RecordingGitInternalService(), true, 5_000);

        assertDoesNotThrow(() -> service.onStop());
        assertNull(service.boundAddress());
    }

    @Test
    void stoppingImmediatelyAfterStartDoesNotLeaveListenerBound() throws Exception {
        service = newService(new RecordingGitInternalService(), true, 5_000);

        service.onStart();
        service.onStop();
        Thread.sleep(100);

        assertNull(service.boundAddress());
    }

    @Test
    void concurrentClientsAreDispatchedIndependently() throws Exception {
        RecordingGitInternalService gitService = new RecordingGitInternalService();
        InetSocketAddress address = startService(gitService, 5_000);
        ExecutorService clients = Executors.newFixedThreadPool(5);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(clients.submit(() -> request(address, new byte[0])));
            }
            for (Future<byte[]> future : futures) {
                assertArrayEquals(HANDLED, future.get(2, TimeUnit.SECONDS));
            }
        } finally {
            clients.shutdownNow();
        }

        Set<String> requestIds = new HashSet<>();
        for (Call call : gitService.awaitCalls(5)) {
            requestIds.add(call.requestId());
        }
        assertEquals(5, requestIds.size());
    }

    @Test
    void uploadPackForMissingRepositoryReachesRealGitServiceWithoutCreatingRepository() throws Exception {
        RecordingGitRepositoryProvider repositoryProvider = new RecordingGitRepositoryProvider();
        GitInternalService gitService = new GitInternalService(repositoryProvider, new OrionEventManager());
        InetSocketAddress address = startService(gitService, 5_000);

        byte[] response = request(address, pktLine("git-upload-pack /missing.git\0host=localhost\0"));

        assertArrayEquals(new byte[0], response);
        assertEquals("missing", repositoryProvider.lastExistsRepository);
        assertNull(repositoryProvider.lastCreatedRepository);
    }

    @Test
    void receivePackForMissingRepositoryRequiresAuthorizationBeforeCreatingRepository() throws Exception {
        RecordingGitRepositoryProvider repositoryProvider = new RecordingGitRepositoryProvider();
        GitInternalService gitService = new GitInternalService(repositoryProvider, new OrionEventManager());
        InetSocketAddress address = startService(gitService, 5_000);

        byte[] response = request(address, pktLine("git-receive-pack /missing.git\0host=localhost\0"));

        assertEquals("0015ERR ACCESS_DENIED0000", new String(response, StandardCharsets.UTF_8));
        assertEquals("missing", repositoryProvider.lastExistsRepository);
        assertNull(repositoryProvider.lastCreatedRepository);
    }

    private InetSocketAddress startService(GitInternalService gitService, int socketTimeoutMillis) throws Exception {
        service = newService(gitService, true, socketTimeoutMillis);
        service.onStart();
        return awaitBoundAddress(service);
    }

    private GitNativeTransportService newService(
            GitInternalService gitService,
            boolean enabled,
            int socketTimeoutMillis) {
        GitTransportConfig config = new GitTransportConfig("127.0.0.1", 0);
        config.setBacklog(10);
        config.setEnabled(enabled);
        executor = new OrionExecutor(4, new OrionThreadFactory());
        return new GitNativeTransportService(config, gitService, executor, socketTimeoutMillis);
    }

    private static Constructor<?> injectConstructor() {
        for (Constructor<?> constructor : GitNativeTransportService.class.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                return constructor;
            }
        }
        throw new AssertionError("Missing @Inject constructor");
    }

    private static InetSocketAddress awaitBoundAddress(GitNativeTransportService service) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            InetSocketAddress address = service.boundAddress();
            if (address != null) {
                return address;
            }
            Thread.sleep(10);
        }
        fail("Git native transport did not bind a listener socket");
        return null;
    }

    private static void awaitConnectionFailure(InetSocketAddress address) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket ignored = connect(address)) {
                Thread.sleep(10);
            } catch (IOException expected) {
                return;
            }
        }
        fail("Git native transport still accepted connections after shutdown");
    }

    private static byte[] request(InetSocketAddress address, byte[] content) throws IOException {
        try (Socket socket = connect(address)) {
            socket.setSoTimeout(2_000);
            if (content.length > 0) {
                socket.getOutputStream().write(content);
                socket.getOutputStream().flush();
            }
            socket.shutdownOutput();
            return socket.getInputStream().readAllBytes();
        }
    }

    private static Socket connect(InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", address.getPort()), 1_000);
        return socket;
    }

    private static byte[] pktLine(String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String length = "%04x".formatted(payloadBytes.length + 4);
        byte[] prefix = length.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[prefix.length + payloadBytes.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(payloadBytes, 0, result, prefix.length, payloadBytes.length);
        return result;
    }

    private record Call(SecurityContext securityContext, String clientId, String requestId, Throwable failure) {
    }

    private static final class RecordingGitInternalService extends GitInternalService {
        private final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
        private final AtomicBoolean failNextCall = new AtomicBoolean();
        private final AtomicBoolean readBeforeResponding = new AtomicBoolean();

        private RecordingGitInternalService() {
            super(new RecordingGitRepositoryProvider(), new OrionEventManager());
        }

        private void failNextCall() {
            failNextCall.set(true);
        }

        private void readBeforeResponding() {
            readBeforeResponding.set(true);
        }

        @Override
        public void service(
                SecurityContext securityContext,
                String clientId,
                StandardStreams streams,
                String requestId,
                Function<InputStream, GitCommand> cmdResolved) {
            Throwable failure = null;
            try {
                if (failNextCall.getAndSet(false)) {
                    throw new IllegalStateException("simulated git service failure");
                }
                if (readBeforeResponding.get()) {
                    streams.getInputStream().read();
                }
                streams.getOutputStream().write(HANDLED);
                streams.getOutputStream().flush();
            } catch (IOException e) {
                failure = new UncheckedIOException(e);
                throw (UncheckedIOException) failure;
            } catch (RuntimeException e) {
                failure = e;
                throw e;
            } finally {
                calls.add(new Call(securityContext, clientId, requestId, failure));
            }
        }

        private Call awaitCall() throws InterruptedException {
            Call call = calls.poll(2, TimeUnit.SECONDS);
            if (call == null) {
                fail("Git service was not called");
            }
            return call;
        }

        private List<Call> awaitCalls(int count) throws InterruptedException {
            List<Call> result = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (result.size() < count && System.nanoTime() < deadline) {
                Call call = calls.poll(10, TimeUnit.MILLISECONDS);
                if (call != null) {
                    result.add(call);
                }
            }
            assertEquals(count, result.size(), "Git service call count");
            return result;
        }
    }

    private static final class RecordingGitRepositoryProvider implements GitRepositoryProvider {
        private String lastExistsRepository;
        private String lastCreatedRepository;

        @Override
        public boolean exists(String repositoryName) {
            lastExistsRepository = repositoryName;
            return false;
        }

        @Override
        public Result<GitRepository> find(String repositoryName) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }

        @Override
        public Result<GitRepository> findOrCreate(String repositoryName) {
            lastCreatedRepository = repositoryName;
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }
    }
}
