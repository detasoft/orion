package pro.deta.orion.transport.git;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.output.NullOutputStream;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ClientConnectionResource;
import pro.deta.orion.auth.check.rule.ConnectionAccessRules;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.git.GitInternalService;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.transport.git.netty.GitMinimalWireHandler;
import pro.deta.orion.util.stream.StandardStreams;
import pro.deta.orion.util.stream.StreamUtils;

import java.io.*;
import java.net.*;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

@Slf4j
@Singleton
public class GitNativeTransportService implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    private static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 5 * 1000;

    private final GitTransportConfig config;
    private final GitInternalService gitInternalService;
    private final OrionExecutor orionExecutor;
    private final NativeGitRepositoryProvider nativeRepositoryProvider;
    private final Function<String, SecurityContext> nativeSecurityContextFactory;
    private final int socketTimeoutMillis;
    private final ChannelGroup nativeClientChannels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private volatile ServerSocket listenSock;
    private volatile Channel nativeServerChannel;
    private volatile EventLoopGroup nativeBossGroup;
    private volatile EventLoopGroup nativeWorkerGroup;
    private volatile boolean stopRequested;

    @Inject
    public GitNativeTransportService(
            GitTransportConfig config,
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor) {
        this(
                config,
                gitInternalService,
                orionExecutor,
                new InMemoryNativeGitRepositoryProvider(),
                DEFAULT_SOCKET_TIMEOUT_MILLIS);
    }

    GitNativeTransportService(
            GitTransportConfig config,
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor,
            int socketTimeoutMillis) {
        this(
                config,
                gitInternalService,
                orionExecutor,
                new InMemoryNativeGitRepositoryProvider(),
                socketTimeoutMillis);
    }

    GitNativeTransportService(
            GitTransportConfig config,
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor,
            NativeGitRepositoryProvider nativeRepositoryProvider,
            int socketTimeoutMillis) {
        this(
                config,
                gitInternalService,
                orionExecutor,
                nativeRepositoryProvider,
                socketTimeoutMillis,
                requestId -> SecurityContext.createContext()
                        .withRequestId(requestId));
    }

    GitNativeTransportService(
            GitTransportConfig config,
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor,
            NativeGitRepositoryProvider nativeRepositoryProvider,
            int socketTimeoutMillis,
            Function<String, SecurityContext> nativeSecurityContextFactory) {
        this.config = config;
        this.gitInternalService = gitInternalService;
        this.orionExecutor = orionExecutor;
        this.nativeRepositoryProvider = nativeRepositoryProvider;
        this.nativeSecurityContextFactory = Objects.requireNonNull(
                nativeSecurityContextFactory,
                "nativeSecurityContextFactory");
        this.socketTimeoutMillis = socketTimeoutMillis;
    }

    public void onStart() {
        if (isEnabled()) {
            stopRequested = false;
            if (isJGitImplementation()) {
                listenJGitService();
            } else {
                listenNativeService();
            }
        }
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public boolean isRunning() {
        return boundAddress() != null;
    }

    private boolean isJGitImplementation() {
        return "jgit".equalsIgnoreCase(
                System.getProperty("orion.git.transport.implementation"));
    }

    private void listenJGitService() {
        if (gitInternalService == null || orionExecutor == null) {
            throw new IllegalStateException(
                    "JGit git:// transport requires GitInternalService and OrionExecutor");
        }
        try {
            InetAddress serverSocketAddress = InetAddress.getByName(config.getAddress());
            ServerSocket serverSocket = new ServerSocket(
                    config.getPort(),
                    config.getBacklog(),
                    serverSocketAddress);
            listenSock = serverSocket;
            if (stopRequested) {
                serverSocket.close();
                return;
            }
            log.warn("Listening on {}:{} [{}]", config.getAddress(), config.getPort(), serverSocketAddress);
            orionExecutor.newDedicatedThread(() -> {
                try {
                    Socket socket;
                    while (!stopRequested && (socket = serverSocket.accept()) != null) {
                        Socket finalSocket = socket;
                        orionExecutor.submit(() -> {
                            newConnectionInternal(finalSocket);
                        });
                    }
                } catch (SocketException e) {
                    if (!"Socket closed".equals(e.getMessage())) {
                        log.error("Socket exception: ", e);
                        throw new RuntimeException(e);
                    } else {
                        log.warn("Socket closed");
                    }
                } catch (IOException e) {
                    log.error("Socket exception: ", e);
                    throw new RuntimeException(e);
                }
            }).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void listenNativeService() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        boolean started = false;
        try {
            Channel channel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, config.getBacklog())
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            initializeNativeChannel(channel);
                        }
                    })
                    .bind(new InetSocketAddress(
                            InetAddress.getByName(config.getAddress()),
                            config.getPort()))
                    .sync()
                    .channel();
            nativeBossGroup = bossGroup;
            nativeWorkerGroup = workerGroup;
            nativeServerChannel = channel;
            started = true;
            if (stopRequested) {
                stopNativeService();
                return;
            }
            log.warn("Listening on {}:{} [native-netty]", config.getAddress(), config.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (!started) {
                bossGroup.shutdownGracefully().awaitUninterruptibly();
                workerGroup.shutdownGracefully().awaitUninterruptibly();
            }
        }
    }

    private void initializeNativeChannel(SocketChannel channel) {
        String requestId = UUID.randomUUID().toString();
        SecurityContext securityContext = nativeSecurityContext(requestId);
        try {
            accessEnforcer().require(
                    securityContext,
                    ClientConnectionResource.of(channel.remoteAddress()),
                    ConnectionAccessRules.localOnly());
            log.debug("Native Git client connected {} via {}", requestId, config);
            nativeClientChannels.add(channel);
            ByteBuf output = channel.alloc().buffer(
                    GitNativeClientOutput.BUFFER_CAPACITY,
                    GitNativeClientOutput.BUFFER_CAPACITY);
            channel.closeFuture().addListener(ignored -> output.release());
            GitNativeClientOutput clientOutput = new GitNativeClientOutput(
                    output,
                    chunk -> channel.writeAndFlush(chunk)
                            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE));
            GitMinimalWireMachine machine = new GitMinimalWireMachine(
                    channel.alloc(),
                    clientOutput,
                    nativeRepositoryProvider,
                    GitNativeRepositoryAccessHook.ALLOW_ALL);
            channel.pipeline().addLast(new GitMinimalWireHandler(machine));
        } catch (OrionSecurityException e) {
            log.warn(e.getMessage());
            channel.close();
        }
    }

    private SecurityContext nativeSecurityContext(String requestId) {
        SecurityContext securityContext = Objects.requireNonNull(
                nativeSecurityContextFactory.apply(requestId),
                "nativeSecurityContext");
        if (securityContext.getRequestId() == null) {
            securityContext.withRequestId(requestId);
        }
        return securityContext;
    }

    private void newConnectionInternal(Socket finalSocket) {
        String requestId = UUID.randomUUID().toString();
        SecurityContext securityContext = SecurityContext.createContext().withRequestId(requestId);

        try {
            accessEnforcer().require(
                    securityContext,
                    ClientConnectionResource.of(finalSocket.getRemoteSocketAddress()),
                    ConnectionAccessRules.localOnly());
            log.debug("Client connected {} via {}", requestId, config);
            finalSocket.setSoTimeout(socketTimeoutMillis);
            try (StandardStreams streams = StreamUtils.newInstance(
                    finalSocket.getInputStream(),
                    finalSocket.getOutputStream(),
                    NullOutputStream.INSTANCE)) {
                gitInternalService.service(
                        securityContext,
                        finalSocket.getRemoteSocketAddress().toString(),
                        streams,
                        requestId,
                        GitInternalService::parse);
            }
        } catch (OrionSecurityException e) {
            log.warn(e.getMessage());
        } catch (IOException e) {
            log.error("Error while serving client {}", requestId, e);
        } finally {
            try {
                finalSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void onStop() {
        stopRequested = true;
        stopNativeService();
        try {
            if (listenSock != null)
                listenSock.close();
        } catch (IOException e) {
            log.error("Error while closing socket.", e);
        } finally {
            listenSock = null;
        }
    }

    private void stopNativeService() {
        Channel serverChannel = nativeServerChannel;
        nativeServerChannel = null;
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        nativeClientChannels.close().awaitUninterruptibly();
        EventLoopGroup workerGroup = nativeWorkerGroup;
        nativeWorkerGroup = null;
        EventLoopGroup bossGroup = nativeBossGroup;
        nativeBossGroup = null;
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().awaitUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }

    InetSocketAddress boundAddress() {
        ServerSocket socket = listenSock;
        if (socket != null && socket.isBound() && !socket.isClosed()) {
            return (InetSocketAddress) socket.getLocalSocketAddress();
        }
        Channel channel = nativeServerChannel;
        if (channel != null
                && channel.isOpen()
                && channel.localAddress() instanceof InetSocketAddress address) {
            return address;
        }
        return null;
    }
}
