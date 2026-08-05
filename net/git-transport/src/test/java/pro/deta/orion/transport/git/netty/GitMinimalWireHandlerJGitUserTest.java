package pro.deta.orion.transport.git.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(20)
class GitMinimalWireHandlerJGitUserTest {
    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("JGit can push through one minimal wire connection and fetch through the next")
    void jgitPushesInitialCommitThenReconnectsAndFetchesIt() throws Exception {
        InMemoryNativeGitRepositoryProvider repositoryProvider =
                new InMemoryNativeGitRepositoryProvider();
        try (MinimalGitServer server = new MinimalGitServer(
                repositoryProvider,
                repositorySecurityContext("project", true, true))) {
            server.start();
            String remoteUri = "git://127.0.0.1:"
                    + server.port()
                    + "/project";

            ObjectId initialCommit = createSourceCommit();

            try (Git source = Git.open(tempDir.resolve("source").toFile())) {
                callWithTimeout(
                        () -> source.push()
                                .setRemote(remoteUri)
                                .setTimeout(5)
                                .setRefSpecs(new RefSpec(
                                        "refs/heads/master:refs/heads/master"))
                                .call(),
                        server);
            }
            assertThat(server.awaitClosedConnections(1)).isTrue();

            Path fetchDirectory = tempDir.resolve("fetch-target");
            try (Git target = Git.init()
                    .setDirectory(fetchDirectory.toFile())
                    .call()) {
                callWithTimeout(
                        () -> target.fetch()
                                .setRemote(remoteUri)
                                .setTimeout(5)
                                .setRefSpecs(new RefSpec(
                                        "+refs/heads/master:refs/remotes/origin/master"))
                                .call(),
                        server);

                ObjectId fetchedCommit = target.getRepository()
                        .resolve("refs/remotes/origin/master");
                assertThat(fetchedCommit).isEqualTo(initialCommit);
                assertThat(readFile(
                        target.getRepository(),
                        fetchedCommit,
                        "README.md"))
                        .isEqualTo("hello through GitMinimalWireHandler\n");
            }

            assertThat(server.connections()).isEqualTo(2);
            assertThat(server.awaitClosedConnections(2)).isTrue();
        }
    }

    private ObjectId createSourceCommit() throws Exception {
        Path sourceDirectory = tempDir.resolve("source");
        Files.createDirectories(sourceDirectory);
        Files.writeString(
                sourceDirectory.resolve("README.md"),
                "hello through GitMinimalWireHandler\n",
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

    private static <T> T callWithTimeout(
            Callable<T> operation,
            MinimalGitServer server) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(operation);
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new AssertionError(
                    "JGit operation failed. Server events:\n"
                            + String.join("\n", server.events()),
                    error.getCause());
        } catch (java.util.concurrent.TimeoutException error) {
            future.cancel(true);
            throw new AssertionError(
                    "JGit operation timed out. Server events:\n"
                            + String.join("\n", server.events()),
                    error);
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
                assertThat(treeWalk).isNotNull();
                byte[] data = repository.open(treeWalk.getObjectId(0))
                        .getBytes();
                return new String(data, StandardCharsets.UTF_8);
            }
        }
    }

    private static SecurityContext repositorySecurityContext(
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
                .withUserIdentity(new InternalUserImpl(
                        "git-user",
                        List.of(grant.toAccessControl())));
    }

    private static final class MinimalGitServer implements AutoCloseable {
        private final InMemoryNativeGitRepositoryProvider repositoryProvider;
        private final SecurityContext securityContext;
        private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final EventLoopGroup workerGroup = new NioEventLoopGroup(1);
        private final AtomicInteger connections = new AtomicInteger();
        private final CountDownLatch closedConnections =
                new CountDownLatch(2);
        private final List<String> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private io.netty.channel.Channel serverChannel;

        MinimalGitServer(
                InMemoryNativeGitRepositoryProvider repositoryProvider,
                SecurityContext securityContext) {
            this.repositoryProvider = repositoryProvider;
            this.securityContext = securityContext;
        }

        void start() throws InterruptedException {
            serverChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            int connection = connections.incrementAndGet();
                            events.add(connection + " accepted");
                            ByteBuf output = channel.alloc().buffer(
                                    GitNativeClientOutput.BUFFER_CAPACITY,
                                    GitNativeClientOutput.BUFFER_CAPACITY);
                            channel.closeFuture()
                                    .addListener(ignored -> output.release());
                            GitNativeClientOutput clientOutput =
                                    new GitNativeClientOutput(
                                            output,
                                            chunk -> {
                                                events.add(connection
                                                        + " write "
                                                        + describe(chunk));
                                                channel.writeAndFlush(chunk)
                                                        .addListener(
                                                                ChannelFutureListener.CLOSE_ON_FAILURE);
                                            });
                            GitMinimalWireMachine machine =
                                    new GitMinimalWireMachine(
                                            channel.alloc(),
                                            clientOutput,
                                            repositoryProvider,
                                            new AuthenticatedNativeRepositoryAccessHook(
                                                    securityContext));
                            channel.pipeline()
                                    .addLast(new TraceHandler(
                                            connection,
                                            events))
                                    .addLast(new GitMinimalWireHandler(machine))
                                    .addLast(new ClosedConnectionTracker(
                                            connection,
                                            events,
                                            closedConnections));
                        }
                    })
                    .bind(new InetSocketAddress("127.0.0.1", 0))
                    .sync()
                    .channel();
        }

        int port() {
            return ((InetSocketAddress) serverChannel.localAddress())
                    .getPort();
        }

        int connections() {
            return connections.get();
        }

        boolean awaitClosedConnections(int count) throws InterruptedException {
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(5);
            while (closedConnections.getCount() > 2L - count
                    && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            return closedConnections.getCount() == 2L - count;
        }

        List<String> events() {
            return List.copyOf(events);
        }

        @Override
        public void close() {
            if (serverChannel != null) {
                serverChannel.close().awaitUninterruptibly();
            }
            bossGroup.shutdownGracefully().awaitUninterruptibly();
            workerGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }

    private static final class ClosedConnectionTracker
            extends ChannelInboundHandlerAdapter {
        private final int connection;
        private final List<String> events;
        private final CountDownLatch closedConnections;

        private ClosedConnectionTracker(
                int connection,
                List<String> events,
                CountDownLatch closedConnections) {
            this.connection = connection;
            this.events = events;
            this.closedConnections = closedConnections;
        }

        @Override
        public void channelInactive(ChannelHandlerContext context)
                throws Exception {
            events.add(connection + " inactive");
            closedConnections.countDown();
            super.channelInactive(context);
        }

        @Override
        public void exceptionCaught(
                ChannelHandlerContext context,
                Throwable cause) throws Exception {
            events.add(connection
                    + " exception "
                    + cause.getClass().getSimpleName()
                    + ": "
                    + cause.getMessage());
            super.exceptionCaught(context, cause);
        }
    }

    private static final class TraceHandler extends ChannelDuplexHandler {
        private final int connection;
        private final List<String> events;

        private TraceHandler(int connection, List<String> events) {
            this.connection = connection;
            this.events = events;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message)
                throws Exception {
            if (message instanceof ByteBuf input) {
                events.add(connection + " read " + describe(input));
            }
            super.channelRead(context, message);
        }

        @Override
        public void write(
                ChannelHandlerContext context,
                Object message,
                ChannelPromise promise) throws Exception {
            if (message instanceof ByteBuf output) {
                events.add(connection
                        + " outbound "
                        + describe(output));
            }
            super.write(context, message, promise);
        }
    }

    private static String describe(ByteBuf buffer) {
        int length = buffer.readableBytes();
        int previewLength = Math.min(length, 96);
        String preview = buffer.toString(
                        buffer.readerIndex(),
                        previewLength,
                        StandardCharsets.ISO_8859_1)
                .replace("\0", "\\0")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return length + " " + preview;
    }
}
