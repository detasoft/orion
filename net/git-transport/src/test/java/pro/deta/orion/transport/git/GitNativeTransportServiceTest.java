package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.git.GitCommand;
import pro.deta.orion.git.GitInternalService;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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

    @TempDir
    private Path tempDir;

    private GitNativeTransportService service;
    private OrionExecutor executor;

    @BeforeEach
    void resetImplementationProperty() {
        System.clearProperty(GitNativeTransportService.IMPLEMENTATION_PROPERTY);
    }

    @AfterEach
    void stopService() throws Exception {
        if (service != null) {
            service.onStop();
        }
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
        System.clearProperty(GitNativeTransportService.IMPLEMENTATION_PROPERTY);
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
    void nativeImplementationIsDefaultWhenPropertyIsUnset() {
        GitTransportConfig config = new GitTransportConfig("127.0.0.1", 0);
        config.setBacklog(10);
        config.setEnabled(true);
        service = new GitNativeTransportService(
                config,
                null,
                null,
                new InMemoryNativeGitRepositoryProvider(),
                5_000);

        service.onStart();

        assertNotNull(service.boundAddress());
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

    @Test
    void nativeImplementationPushesThenFetchesThroughSharedInMemoryRepository() throws Exception {
        System.setProperty(
                GitNativeTransportService.IMPLEMENTATION_PROPERTY,
                "native");
        InetSocketAddress address = startNativeService(
                new RecordingGitInternalService(),
                new InMemoryNativeGitRepositoryProvider(),
                requestId -> repositorySecurityContext(
                        requestId,
                        "project",
                        true,
                        true),
                5_000);
        String remoteUri = "git://127.0.0.1:"
                + address.getPort()
                + "/project";
        ObjectId initialCommit = createSourceCommit();

        try (Git source = Git.open(tempDir.resolve("source").toFile())) {
            callWithTimeout(() -> source.push()
                    .setRemote(remoteUri)
                    .setTimeout(5)
                    .setRefSpecs(new RefSpec(
                            "refs/heads/master:refs/heads/master"))
                    .call());
        }

        Path fetchDirectory = tempDir.resolve("fetch-target");
        try (Git target = Git.init()
                .setDirectory(fetchDirectory.toFile())
                .call()) {
            callWithTimeout(() -> target.fetch()
                    .setRemote(remoteUri)
                    .setTimeout(5)
                    .setRefSpecs(new RefSpec(
                            "+refs/heads/master:refs/remotes/origin/master"))
                    .call());

            ObjectId fetchedCommit = target.getRepository()
                    .resolve("refs/remotes/origin/master");
            assertEquals(initialCommit, fetchedCommit);
            assertEquals(
                    "hello through GitNativeTransportService\n",
                    readFile(
                            target.getRepository(),
                            fetchedCommit,
                            "README.md"));
        }
    }

    @Test
    void nativeImplementationAllowsAnonymousReceivePackWithExplicitAllowAllHook() throws Exception {
        System.setProperty(
                GitNativeTransportService.IMPLEMENTATION_PROPERTY,
                "native");
        InMemoryNativeGitRepositoryProvider repositoryProvider =
                new InMemoryNativeGitRepositoryProvider();
        InetSocketAddress address = startNativeService(
                new RecordingGitInternalService(),
                repositoryProvider,
                requestId -> SecurityContext.createContext()
                        .withRequestId(requestId),
                5_000);

        byte[] response = request(
                address,
                pktLine("git-receive-pack /project.git\0host=localhost\0"));

        assertTrue(new String(response, StandardCharsets.UTF_8)
                .contains("capabilities^{}"));
        assertTrue(repositoryProvider.exists("/project.git"));
    }

    private InetSocketAddress startService(
            GitInternalService gitService,
            int socketTimeoutMillis) throws Exception {
        service = newService(gitService, true, socketTimeoutMillis);
        service.onStart();
        return awaitBoundAddress(service);
    }

    private InetSocketAddress startNativeService(
            GitInternalService gitService,
            NativeGitRepositoryProvider repositoryProvider,
            Function<String, SecurityContext> securityContextFactory,
            int socketTimeoutMillis) throws Exception {
        service = newNativeService(
                gitService,
                true,
                repositoryProvider,
                securityContextFactory,
                socketTimeoutMillis);
        service.onStart();
        return awaitBoundAddress(service);
    }

    private GitNativeTransportService newService(
            GitInternalService gitService,
            boolean enabled,
            int socketTimeoutMillis) {
        System.setProperty(
                GitNativeTransportService.IMPLEMENTATION_PROPERTY,
                "jgit");
        GitTransportConfig config = new GitTransportConfig("127.0.0.1", 0);
        config.setBacklog(10);
        config.setEnabled(enabled);
        executor = new OrionExecutor(4, new OrionThreadFactory());
        return new GitNativeTransportService(config, gitService, executor, socketTimeoutMillis);
    }

    private GitNativeTransportService newNativeService(
            GitInternalService gitService,
            boolean enabled,
            NativeGitRepositoryProvider repositoryProvider,
            Function<String, SecurityContext> securityContextFactory,
            int socketTimeoutMillis) {
        GitTransportConfig config = new GitTransportConfig("127.0.0.1", 0);
        config.setBacklog(10);
        config.setEnabled(enabled);
        executor = new OrionExecutor(4, new OrionThreadFactory());
        return new GitNativeTransportService(
                config,
                gitService,
                executor,
                repositoryProvider,
                socketTimeoutMillis,
                securityContextFactory);
    }

    private static SecurityContext repositorySecurityContext(
            String requestId,
            String repositoryName,
            boolean write,
            boolean create) {
        AccessControlDraft.Grant grant =
                new AccessControlDraft.Grant(
                        "repository",
                        new ArrayList<>())
                        .addKey(
                                AccessControl.GrantKey.REPOSITORY,
                                repositoryName);
        if (write) {
            grant.addKey(
                    AccessControl.GrantKey.WRITE,
                    AccessControl.TRUE_STRING);
        }
        if (create) {
            grant.addKey(
                    AccessControl.GrantKey.CREATE,
                    AccessControl.TRUE_STRING);
        }
        return SecurityContext.createContext()
                .withRequestId(requestId)
                .withUserIdentity(new InternalUserImpl(
                        "git-user",
                        List.of(grant.toAccessControl())));
    }

    private ObjectId createSourceCommit() throws Exception {
        Path sourceDirectory = tempDir.resolve("source");
        Files.createDirectories(sourceDirectory);
        Files.writeString(
                sourceDirectory.resolve("README.md"),
                "hello through GitNativeTransportService\n",
                StandardCharsets.UTF_8);
        try (Git source = Git.init()
                .setDirectory(sourceDirectory.toFile())
                .call()) {
            source.add()
                    .addFilepattern("README.md")
                    .call();
            RevCommit commit = source.commit()
                    .setAuthor("Test User", "test@example.com")
                    .setCommitter("Test User", "test@example.com")
                    .setMessage("initial commit")
                    .call();
            return commit.getId();
        }
    }

    private static <T> T callWithTimeout(Callable<T> operation)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(operation);
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new AssertionError(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String readFile(
            Repository repository,
            ObjectId commitId,
            String fileName) throws Exception {
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            try (TreeWalk treeWalk = TreeWalk.forPath(
                    repository,
                    fileName,
                    commit.getTree())) {
                if (treeWalk == null) {
                    fail("Missing file " + fileName);
                }
                byte[] data = repository.open(treeWalk.getObjectId(0))
                        .getBytes();
                return new String(data, StandardCharsets.UTF_8);
            }
        }
    }

    private static Constructor<?> injectConstructor() {
        for (Constructor<?> constructor : GitNativeTransportService.class.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                return constructor;
            }
        }
        throw new AssertionError("Missing @Inject constructor");
    }

    private static InetSocketAddress awaitBoundAddress(
            GitNativeTransportService service) throws InterruptedException {
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
