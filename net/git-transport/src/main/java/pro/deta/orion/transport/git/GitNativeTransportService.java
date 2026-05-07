package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.output.NullOutputStream;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.config.schema.GitTransportConfig;
import pro.deta.orion.git.GitInternalService;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.stream.IOEStreamProvider;
import pro.deta.orion.util.stream.StreamUtils;

import java.io.*;
import java.net.*;
import java.util.UUID;

import static pro.deta.orion.auth.check.PermissionChecks.permissionChecker;

@Slf4j
@Singleton
public class GitNativeTransportService implements OrionApplicationStageEventListener {
    public static final int GIT_TRANSPORT_PRIORITY = 5;
    private final GitTransportConfig config;
    private final GitInternalService gitInternalService;
    private final OrionExecutor orionExecutor;
    private ServerSocket listenSock;

    @Inject
    public GitNativeTransportService(ConfigurationContext configurationContext, GitInternalService gitInternalService, OrionExecutor orionExecutor) {
        this.config = configurationContext.getConfiguration().getTransports().getGit();
        this.gitInternalService = gitInternalService;
        this.orionExecutor = orionExecutor;
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.STARTING, this::onStart).priority(GIT_TRANSPORT_PRIORITY);
        registrar.register(ApplicationState.STOPPING, this::onStop);
    }


    public OrionStageCallResult onStart() {
        if (config.isEnabled()) {
            OrionStageCallResult callResult = new OrionStageCallResult(0);
            callResult.submit(orionExecutor, () -> listenService());
            return callResult;
        }
        return null;
    }

    private void listenService() {
        try {
            InetAddress serverSocketAddress = InetAddress.getByName(config.getAddress());
            listenSock = new ServerSocket(config.getPort(), config.getBacklog(), serverSocketAddress);
            log.warn("Listening on {}:{} [{}]", config.getAddress(), config.getPort(), serverSocketAddress);
            orionExecutor.newDedicatedThread(() -> {
                try {
                    Socket socket;
                    while ((socket = listenSock.accept()) != null) {
                        Socket finalSocket = socket;
                        orionExecutor.submit(() -> {
                            newConnectionInternal(finalSocket);
                        });
                    }
                } catch (SocketException e) {
                    if (!e.getMessage().equals("Socket closed")) {
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
            permissionChecker().requireLocalConnection(securityContext, finalSocket.getRemoteSocketAddress());
            log.debug("Client connected {} via {}", requestId, config);
            finalSocket.setSoTimeout(5 * 1000);
            try (IOEStreamProvider streams = StreamUtils.newInstance(finalSocket.getInputStream(), finalSocket.getOutputStream(), NullOutputStream.INSTANCE)) {
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

    public OrionStageCallResult onStop() {
        try {
            if (listenSock != null)
                listenSock.close();
        } catch (IOException e) {
            log.error("Error while closing socket.", e);
        }
        return null;
    }
}
