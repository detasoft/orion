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
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.config.schema.SshTransportConfig;
import pro.deta.orion.crypto.SshHostKeyService;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.transport.git.ssh.SshCommandFactory;
import pro.deta.orion.util.*;

import javax.inject.Provider;
import java.io.*;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GitSshTransportService implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    public static final AttributeRepository.AttributeKey<UserIdentity> SSH_AUTHENTICATED_USER = new AttributeRepository.AttributeKey<>();
    private static final long STOP_WAIT_MILLIS = 500;

    private final OrionConfiguration orionConfiguration;
    private final SshServer sshd = SshServer.setUpDefaultServer();

    private final SshCommandFactory commandFactory;
    private final Provider<SshHostKeyService> sshHostKeyService;
    private final OrionSSHPasswordAuthenticator orionPasswordAuthenticator;


    public void onStart() {
        SshTransportConfig config = sshTransportConfig();
        if (!isEnabled()) {
            return;
        }
        SecurityUtils.registerSecurityProvider(new BouncyCastleSecurityProviderRegistrar());
        if (SecurityUtils.isBouncyCastleRegistered()) {
            log.info("BouncyCastle is registered as a JCE provider");
        }
        SecurityUtils.registerSecurityProvider(new EdDSASecurityProviderRegistrar());
        if (SecurityUtils.isProviderRegistered("EdDSA")) {
            log.info("EdDSA is registered as a JCE provider");
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

            sshd.setKeyPairProvider(new MappedKeyPairProvider(sshHostKeyService.get().getKeyPairs()));

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
            throw new IllegalStateException("Cannot bind SSH transport " + config, e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isEnabled() {
        return sshTransportConfig().isEnabled();
    }

    public boolean isRunning() {
        return sshd.isStarted();
    }

    private SshTransportConfig sshTransportConfig() {
        return orionConfiguration.getTransport().getSsh();
    }

    public void onStop() {
        try {
            boolean closed = sshd.close(true).await(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS, CancelOption.CANCEL_ON_TIMEOUT);
            if (!closed) {
                log.warn("SSHD close did not complete within {}ms; continuing shutdown.", STOP_WAIT_MILLIS);
            }
        } catch (IOException e) {
            log.error("Error while closing sshd.", e);
        }
    }

}
