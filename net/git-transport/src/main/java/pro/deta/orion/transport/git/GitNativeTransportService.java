package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.parser.wire.GitBlockingWireSession;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class GitNativeTransportService implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    private static final long STOP_WAIT_MILLIS = 500;

    private final GitTransportConfig config;
    private final GitNativeRepositoryService repositoryService;
    private final Object lifecycleLock = new Object();
    private final Map<Socket, Thread> activeConnections = new LinkedHashMap<>();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    @Inject
    public GitNativeTransportService(
            GitTransportConfig config,
            GitNativeRepositoryService repositoryService) {
        this.config = config;
        this.repositoryService = repositoryService;
    }

    @Override
    public void onStart() {
        synchronized (lifecycleLock) {
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
    }

    @Override
    public void onStop() {
        ServerSocket listener;
        Thread listenerThread;
        List<Socket> connections;
        synchronized (lifecycleLock) {
            listener = serverSocket;
            serverSocket = null;
            listenerThread = acceptThread;
            acceptThread = null;
            connections = new ArrayList<>(activeConnections.keySet());
        }
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MILLIS);
        closeListener(listener);
        for (Socket connection : connections) {
            closeConnection(connection);
        }
        awaitListener(listenerThread, deadline);
        awaitConnections(connections, deadline);
    }

    private static void closeListener(ServerSocket listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.close();
        } catch (IOException error) {
            log.warn("Failed to close native Git listener", error);
        }
    }

    private static void closeConnection(Socket connection) {
        try {
            connection.close();
        } catch (IOException error) {
            log.warn("Failed to close native Git connection", error);
        }
    }

    private static void awaitListener(Thread listenerThread, long deadline) {
        if (listenerThread == null) {
            return;
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                listenerThread.join(Math.max(
                        1,
                        TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            if (listenerThread.isAlive()) {
                log.warn("Native Git listener did not stop within {}ms", STOP_WAIT_MILLIS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping native Git listener", error);
        }
    }

    private void awaitConnections(List<Socket> connections, long deadline) {
        synchronized (lifecycleLock) {
            while (activeCount(connections) > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    log.warn(
                            "Native Git connections did not stop within {}ms; active={}",
                            STOP_WAIT_MILLIS,
                            activeCount(connections));
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while stopping native Git connections", error);
                    return;
                }
            }
        }
    }

    private int activeCount(List<Socket> connections) {
        int count = 0;
        for (Socket connection : connections) {
            if (activeConnections.containsKey(connection)) {
                count++;
            }
        }
        return count;
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

    public int boundPort() {
        ServerSocket listener = serverSocket;
        return listener == null || listener.isClosed()
                ? 0
                : listener.getLocalPort();
    }

    private void acceptLoop(ServerSocket listener) {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                startConnection(listener, socket);
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

    private void startConnection(ServerSocket listener, Socket socket) {
        Thread handler = Thread.ofVirtual()
                .name("orion-native-git-connection-", 0)
                .unstarted(() -> handleConnection(socket));
        boolean started = false;
        try {
            synchronized (lifecycleLock) {
                if (serverSocket != listener || listener.isClosed()) {
                    return;
                }
                activeConnections.put(socket, handler);
                handler.start();
                started = true;
            }
        } finally {
            if (!started) {
                synchronized (lifecycleLock) {
                    activeConnections.remove(socket);
                    lifecycleLock.notifyAll();
                }
                closeConnection(socket);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            serveConnection(socket);
        } catch (IOException error) {
            if (!socket.isClosed()) {
                log.warn("Native Git connection failed", error);
            }
        } catch (Exception error) {
            log.warn("Native Git connection failed", error);
        } finally {
            closeConnection(socket);
            synchronized (lifecycleLock) {
                activeConnections.remove(socket);
                lifecycleLock.notifyAll();
            }
        }
    }

    private void serveConnection(Socket socket) throws IOException {
        InputStreamBufferedByteInput input =
                new InputStreamBufferedByteInput(socket.getInputStream());
        OutputStreamBufferedByteOutput output =
                new OutputStreamBufferedByteOutput(socket.getOutputStream());
        GitWireBootstrap bootstrap = GitWireBootstrap.nativeDaemon(input, output);
        new GitBlockingWireSession(
                repositoryService,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                GitWireConfiguration.allSupported(),
                NativePackfileUriSourceFactory.NONE,
                bootstrap.wire())
                .serveCommand(bootstrap.data());
    }
}
