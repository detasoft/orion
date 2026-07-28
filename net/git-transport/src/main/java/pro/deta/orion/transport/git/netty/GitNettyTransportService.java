package pro.deta.orion.transport.git.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;

import java.net.InetSocketAddress;
import java.util.function.Function;

@Slf4j
public final class GitNettyTransportService
        implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {

    private final GitTransportConfig config;
    private final Function<String, GitRepository> repoLookup; // Phase 2: replace with provider
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private Channel serverChannel;

    public GitNettyTransportService(
            GitTransportConfig config,
            Function<String, GitRepository> repoLookup) {
        this.config = config;
        this.repoLookup = repoLookup;
    }

    @Override
    public void onStart() throws Exception {
        if (!isEnabled()) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        serverChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new GitNativeProtocolAdapter(repoLookup, ch.alloc()));
                    }
                })
                .bind(config.getAddress(), config.getPort())
                .sync()
                .channel();
        log.warn("Listening on {}:{} [Netty/native]", config.getAddress(), config.getPort());
    }

    @Override
    public void onStop() throws Exception {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    /** Returns the bound address, or {@code null} if not running. Used by tests for ephemeral port. */
    public InetSocketAddress boundAddress() {
        Channel ch = serverChannel;
        if (ch == null || !ch.isActive()) {
            return null;
        }
        return (InetSocketAddress) ch.localAddress();
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }
}
