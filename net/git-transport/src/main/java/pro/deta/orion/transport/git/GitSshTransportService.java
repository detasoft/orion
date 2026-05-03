package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.future.CancelOption;
import org.apache.sshd.common.io.IoServiceFactoryFactory;
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory;
import org.apache.sshd.common.keyprovider.MappedKeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.common.util.security.bouncycastle.BouncyCastleSecurityProviderRegistrar;
import org.apache.sshd.common.util.security.eddsa.EdDSASecurityProviderRegistrar;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.CachingPublicKeyAuthenticator;
import org.apache.sshd.server.forward.StaticDecisionForwardingFilter;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.config.schema.SshTransportConfig;
import pro.deta.orion.crypto.ServerKeyService;
import pro.deta.orion.lifecycle.*;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.transport.git.ssh.SshCommandFactory;
import pro.deta.orion.util.*;

import java.io.*;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static pro.deta.orion.transport.git.GitNativeTransportService.GIT_TRANSPORT_PRIORITY;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GitSshTransportService implements AutoCloseable, OrionApplicationStageEventListener {
    public static final AttributeRepository.AttributeKey<UserIdentity> SSH_AUTHENTICATED_USER = new AttributeRepository.AttributeKey<>();

    private final OrionConfiguration orionConfiguration;
    private final SshServer sshd = SshServer.setUpDefaultServer();

    private final SshCommandFactory commandFactory;
    private final ServerKeyService serverKeyService;
    private final OrionSSHPasswordAuthenticator orionPasswordAuthenticator;


    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.INIT, this::onInit);
        registrar.register(ApplicationState.STARTING, this::onStart).priority(GIT_TRANSPORT_PRIORITY);
        registrar.register(ApplicationState.STOPPING, this::onStop);
    }


    public OrionStageCallResult onInit() {
        SecurityUtils.registerSecurityProvider(new BouncyCastleSecurityProviderRegistrar());
        if (SecurityUtils.isBouncyCastleRegistered()) {
            log.info("BouncyCastle is registered as a JCE provider");
        }
        SecurityUtils.registerSecurityProvider(new EdDSASecurityProviderRegistrar());
        if (SecurityUtils.isProviderRegistered("EdDSA")) {
            log.info("EdDSA is registered as a JCE provider");
        }

        return null;
    }

    public OrionStageCallResult onStart() {
        SshTransportConfig config  = orionConfiguration.getTransports().getSsh();
        if (!config.isEnabled()) {
            return null;
        }
        try {
//            CoreModuleProperties.NIO2_READ_TIMEOUT.set(sshd.getParentPropertyResolver(), Duration.ofHours(1));
            System.setProperty(IoServiceFactoryFactory.class.getName(), Nio2ServiceFactoryFactory.class.getName());
//            System.setProperty(IoServiceFactoryFactory.class.getName(), MinaServiceFactoryFactory.class.getName());

            // Create the socket address for binding the SSH server
            InetSocketAddress addr;
            if (OrionUtils.isNullOrEmpty(config.getAddress())) {
                addr = new InetSocketAddress(config.getPort());
            } else {
                addr = new InetSocketAddress(config.getAddress(), config.getPort());
            }

            // Create the SSH server
            sshd.setPort(addr.getPort());
            sshd.setHost(addr.getHostName());

            sshd.setKeyPairProvider(new MappedKeyPairProvider(serverKeyService.getKeyPairs()));

            sshd.setPublickeyAuthenticator(new CachingPublicKeyAuthenticator(orionPasswordAuthenticator));
            sshd.setPasswordAuthenticator(orionPasswordAuthenticator);
//                sshd.setSessionFactory(new SshServerSessionFactory(sshd));
            sshd.setFileSystemFactory(new FileSystemFactory() {
                @Override
                public Path getUserHomeDir(SessionContext sessionContext) throws IOException {
                    return null;
                }

                @Override
                public FileSystem createFileSystem(SessionContext sessionContext) throws IOException {
                    return null;
                }
            });
            sshd.setForwardingFilter(new StaticDecisionForwardingFilter(false));
            sshd.setCommandFactory(commandFactory);
            sshd.setShellFactory(new OrionShell());

            // Set the server id.  This can be queried with:
            //   ssh-keyscan -t rsa,dsa -p 29418 localhost
            log.warn("Listening on {} sshd: {}", addr, sshd.getVersion());
            sshd.start();
        } catch (BindException e) {
            log.error("Bind exception: {}: {}", config, e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    public OrionStageCallResult onStop() {
        try {
            sshd.close(true).await(5, TimeUnit.SECONDS, CancelOption.CANCEL_ON_TIMEOUT);
        } catch (IOException e) {
            log.error("Error while closing sshd.", e);
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        sshd.close();
    }
}
