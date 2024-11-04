package pro.deta.orion.internal.jgit;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.sshd.client.SshClient;
import pro.deta.orion.internal.OrionExecutor;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;

@Singleton
public class OrionClientSshdSessionFactoryProvider implements AutoCloseable {
    private final SshClient sshClient;

    @Inject
    public OrionClientSshdSessionFactoryProvider(OrionExecutor orionExecutor) {
        sshClient = SshClient.setUpDefaultClient();
        sshClient.setScheduledExecutorService(orionExecutor);
        sshClient.start();
    }

    public OrionClientSshdSessionFactory create(KeyPair identity, List<PublicKey> expectedServerPublicKeys) {
        return new OrionClientSshdSessionFactory(identity, expectedServerPublicKeys);
    }

    @Override
    public void close() throws Exception {
        sshClient.close();
    }
}
