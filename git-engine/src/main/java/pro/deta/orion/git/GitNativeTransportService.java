package pro.deta.orion.git;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.output.NullOutputStream;
import pro.deta.orion.auth.*;
import pro.deta.orion.config.GitTransportConfig;
import pro.deta.orion.git.ssh.SshCommandFactory;

import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.Executor;

import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static pro.deta.orion.auth.check.PermissionChecks.permissionChecker;

@AllArgsConstructor
@Slf4j
public class GitNativeTransportService {
    private static final int BACKLOG = 2;
    private final GitTransportConfig config;
    private final GitInternalService gitInternalService;
    private final Executor executor;

    public void start() {
        try {
            InetAddress serverSocketAddress = InetAddress.getByName(config.getAddress());
            try (ServerSocket listenSock = new ServerSocket(config.getPort(), BACKLOG, serverSocketAddress)) {
                log.warn("Listening on " + config.getAddress() +":" + config.getPort() + " [" + serverSocketAddress + "]");
                Socket socket;
                while ((socket = listenSock.accept()) != null) {
                    Socket finalSocket = socket;
                    executor.execute(() -> {
                        try (SecurityContextHolder sch = new SecurityContextHolder()) {
                            String threadName = Thread.currentThread().getName();
                            String requestId = UUID.randomUUID().toString();

                            getSc().with(Permission.REQUEST_ID, requestId);

                            try {
                                permissionChecker().ALLOW_ANONYMOUS_ACCESS.assertThat(new UserIdentity("git-native-user"));
                                permissionChecker().ALLOW_ONLY_LOCAL_CONNECTIONS.assertThat(finalSocket.getRemoteSocketAddress());
                                log.debug("Client connected {} via {}", requestId, config);
                                finalSocket.setSoTimeout(5 * 1000);
                                gitInternalService.service(finalSocket.getRemoteSocketAddress().toString(), finalSocket.getInputStream(), finalSocket.getOutputStream(), new SshCommandFactory.BAOSTeeOutputStream(NullOutputStream.INSTANCE),  requestId, GitInternalService::parse);
                            } catch (SecurityException e) {
                                log.warn(e.getMessage());
                            } catch (IOException e) {
                                log.error("Error while serving client {}", requestId, e);
                            } finally {
                                Thread.currentThread().setName(threadName);
                                try {
                                    finalSocket.close();
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    });
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}