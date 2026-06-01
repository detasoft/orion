package pro.deta.orion.transport.git;

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
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.util.stream.StandardStreams;
import pro.deta.orion.util.stream.StreamUtils;

import java.io.*;
import java.net.*;
import java.util.UUID;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

@Slf4j
@Singleton
public class GitNativeTransportService {
    private static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 5 * 1000;

    private final GitTransportConfig config;
    private final GitInternalService gitInternalService;
    private final OrionExecutor orionExecutor;
    private final int socketTimeoutMillis;
    private volatile ServerSocket listenSock;
    private volatile boolean stopRequested;

    @Inject
    public GitNativeTransportService(
            GitTransportConfig config,
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor) {
        this(config, gitInternalService, orionExecutor, DEFAULT_SOCKET_TIMEOUT_MILLIS);
    }

    GitNativeTransportService(
            GitTransportConfig config,
            GitInternalService gitInternalService,
            OrionExecutor orionExecutor,
            int socketTimeoutMillis) {
        this.config = config;
        this.gitInternalService = gitInternalService;
        this.orionExecutor = orionExecutor;
        this.socketTimeoutMillis = socketTimeoutMillis;
    }

    public void onStart() {
        if (isEnabled()) {
            stopRequested = false;
            listenService();
        }
    }

    public boolean isEnabled() {
        return config != null && config.isEnabled();
    }

    private void listenService() {
        try {
            InetAddress serverSocketAddress = InetAddress.getByName(config.getAddress());
            ServerSocket serverSocket = new ServerSocket(config.getPort(), config.getBacklog(), serverSocketAddress);
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
            try (StandardStreams streams = StreamUtils.newInstance(finalSocket.getInputStream(), finalSocket.getOutputStream(), NullOutputStream.INSTANCE)) {
                gitInternalService.service(securityContext, finalSocket.getRemoteSocketAddress().toString(), streams, requestId, GitInternalService::parse);
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
        try {
            if (listenSock != null)
                listenSock.close();
        } catch (IOException e) {
            log.error("Error while closing socket.", e);
        } finally {
            listenSock = null;
        }
    }

    InetSocketAddress boundAddress() {
        ServerSocket socket = listenSock;
        if (socket == null || !socket.isBound() || socket.isClosed()) {
            return null;
        }
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }
}
