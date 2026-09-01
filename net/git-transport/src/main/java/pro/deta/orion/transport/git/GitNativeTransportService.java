package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitWireBootstrap;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.schema.config.GitTransportConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

@Slf4j
@Singleton
public class GitNativeTransportService implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    private static final long STOP_WAIT_MILLIS = 500;

    private final GitTransportConfig config;
    private final NativeGitRepositoryProvider nativeRepositoryProvider;

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    @Inject
    public GitNativeTransportService(
            GitTransportConfig config,
            NativeGitRepositoryProvider nativeRepositoryProvider) {
        this.config = config;
        this.nativeRepositoryProvider = nativeRepositoryProvider;
    }

    @Override
    public void onStart() {
        if (!isEnabled() || isRunning()) {
            return;
        }
        try {
            ServerSocket listener = new ServerSocket();
            listener.bind(
                    new InetSocketAddress(
                            config.getAddress(),
                            config.getPort()),
                    config.getBacklog());
            serverSocket = listener;
            acceptThread = new Thread(
                    () -> acceptLoop(listener),
                    "orion-native-git-accept");
            acceptThread.start();
            log.warn(
                    "Native Git transport listening on {}:{}",
                    listener.getInetAddress().getHostAddress(),
                    listener.getLocalPort());
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot bind native Git transport " + config,
                    error);
        }
    }

    @Override
    public void onStop() {
        ServerSocket listener = serverSocket;
        serverSocket = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException error) {
                log.warn("Failed to close native Git listener", error);
            }
        }
        Thread thread = acceptThread;
        acceptThread = null;
        if (thread == null) {
            return;
        }
        try {
            thread.join(STOP_WAIT_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping native Git listener", error);
        }
    }

    @Override
    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public boolean isRunning() {
        ServerSocket listener = serverSocket;
        return listener != null && !listener.isClosed();
    }

    int boundPort() {
        ServerSocket listener = serverSocket;
        return listener == null || listener.isClosed()
                ? 0
                : listener.getLocalPort();
    }

    private void acceptLoop(ServerSocket listener) {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                Thread.ofVirtual()
                        .name("orion-native-git-connection-", 0)
                        .start(() -> handleConnection(socket));
            } catch (SocketException error) {
                if (!listener.isClosed()) {
                    log.warn("Native Git listener socket failed", error);
                }
                return;
            } catch (IOException error) {
                log.warn("Native Git listener accept failed", error);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            serveConnection(socket);
        } catch (Exception error) {
            log.warn("Native Git connection failed", error);
        }
    }

    private void serveConnection(Socket socket) throws IOException {
        try (InputStreamBufferedByteInput input =
                     new InputStreamBufferedByteInput(socket.getInputStream())) {
            OutputStreamBufferedByteOutput output =
                    new OutputStreamBufferedByteOutput(socket.getOutputStream());
            GitWireBootstrap bootstrap = GitWireBootstrap.nativeDaemon(input, output);
            new GitBlockingWireSession(
                    nativeRepositoryProvider,
                    GitNativeRepositoryAccessHook.ALLOW_ALL,
                    GitWireConfiguration.allSupported(),
                    NativePackfileUriSourceFactory.NONE,
                    bootstrap.wire())
                    .serveCommand(bootstrap.data());
        }
    }
}
