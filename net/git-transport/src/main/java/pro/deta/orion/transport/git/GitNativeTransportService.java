package pro.deta.orion.transport.git;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
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
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ClientConnectionResource;
import pro.deta.orion.auth.check.rule.ConnectionAccessRules;
import pro.deta.orion.schema.config.GitPackfileUriConfig;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriBuilder;
import pro.deta.orion.git.nativestorage.upload.PublishedPackfileUriSource;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.transport.git.netty.GitMinimalWireHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

@Slf4j
@Singleton
public class GitNativeTransportService implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    private static final long NETTY_SHUTDOWN_QUIET_PERIOD_MILLIS = 0;
    private static final long NETTY_SHUTDOWN_TIMEOUT_MILLIS = 1_000;

    private final GitTransportConfig config;
    private final NativeGitRepositoryProvider nativeRepositoryProvider;
    private final Function<String, SecurityContext> nativeSecurityContextFactory;
    private final NativePackfileUriSourceFactory packfileUriSourceFactory;
    private final ChannelGroup nativeClientChannels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private volatile Channel nativeServerChannel;
    private volatile EventLoopGroup nativeBossGroup;
    private volatile EventLoopGroup nativeWorkerGroup;
    private volatile boolean stopRequested;

    @Inject
    public GitNativeTransportService(
            GitTransportConfig config,
            NativeGitRepositoryProvider nativeRepositoryProvider) {
        this(
                config,
                nativeRepositoryProvider,
                requestId -> SecurityContext.createContext().withRequestId(requestId),
                packfileUriSourceFactory(config));
    }

    GitNativeTransportService(
            GitTransportConfig config,
            NativeGitRepositoryProvider nativeRepositoryProvider,
            Function<String, SecurityContext> nativeSecurityContextFactory) {
        this(
                config,
                nativeRepositoryProvider,
                nativeSecurityContextFactory,
                packfileUriSourceFactory(config));
    }

    GitNativeTransportService(
            GitTransportConfig config,
            NativeGitRepositoryProvider nativeRepositoryProvider,
            Function<String, SecurityContext> nativeSecurityContextFactory,
            NativePackfileUriSourceFactory packfileUriSourceFactory) {
        this.config = config;
        this.nativeRepositoryProvider = nativeRepositoryProvider;
        this.nativeSecurityContextFactory = Objects.requireNonNull(
                nativeSecurityContextFactory,
                "nativeSecurityContextFactory");
        this.packfileUriSourceFactory = Objects.requireNonNull(
                packfileUriSourceFactory,
                "packfileUriSourceFactory");
    }

    public void onStart() {
        if (isEnabled()) {
            stopRequested = false;
            listenNativeService();
        }
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    public boolean isRunning() {
        return boundAddress() != null;
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
                shutdownNativeGroup(bossGroup);
                shutdownNativeGroup(workerGroup);
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
            GitNativeClientOutput clientOutput = new GitNativeClientOutput(
                    channel.alloc(),
                    buffer -> writeToChannel(channel, buffer));
            channel.closeFuture().addListener(ignored -> clientOutput.close());
            GitMinimalWireMachine machine = new GitMinimalWireMachine(
                    channel.alloc(),
                    clientOutput,
                    nativeRepositoryProvider,
                    GitNativeRepositoryAccessHook.ALLOW_ALL,
                    GitWireConfiguration.allSupported(),
                    packfileUriSourceFactory);
            channel.pipeline().addLast(new GitMinimalWireHandler(machine));
        } catch (OrionSecurityException e) {
            log.warn(e.getMessage());
            channel.close();
        }
    }

    private static CompletionStage<Void> writeToChannel(
            Channel channel,
            ByteBuf buffer) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ByteBuf outbound = buffer.retainedSlice(
                buffer.readerIndex(),
                buffer.readableBytes());
        channel.writeAndFlush(outbound).addListener(future -> {
            if (future.isSuccess()) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(future.cause());
                channel.close();
            }
        });
        return completion;
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

    public void onStop() {
        stopRequested = true;
        stopNativeService();
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
            shutdownNativeGroup(workerGroup);
        }
        if (bossGroup != null) {
            shutdownNativeGroup(bossGroup);
        }
    }

    private static void shutdownNativeGroup(EventLoopGroup group) {
        group.shutdownGracefully(
                        NETTY_SHUTDOWN_QUIET_PERIOD_MILLIS,
                        NETTY_SHUTDOWN_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS)
                .awaitUninterruptibly();
    }

    InetSocketAddress boundAddress() {
        Channel channel = nativeServerChannel;
        if (channel != null
                && channel.isOpen()
                && channel.localAddress() instanceof InetSocketAddress address) {
            return address;
        }
        return null;
    }

    private static NativePackfileUriSourceFactory packfileUriSourceFactory(
            GitTransportConfig config) {
        GitPackfileUriConfig packfileUri = config == null
                ? null
                : config.getPackfileUri();
        if (packfileUri == null
                || !packfileUri.isConfigured()
                || packfileUri.isAuto()) {
            return NativePackfileUriSourceFactory.NONE;
        }
        String baseUri = packfileUri.getBaseUri();
        return (data, repository) -> new PublishedPackfileUriSource(
                repository,
                packId -> NativePackfileUriBuilder.packUri(
                        baseUri,
                        data.getRepositoryPath(),
                        packId));
    }
}
