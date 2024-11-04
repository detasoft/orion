package pro.deta.orion.internal.jgit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class OrionClientSshdSessionFactory extends SshdSessionFactory {
    private final KeyPair identity;
    private final List<PublicKey> expectedServerPublicKeys;

    @Override
    protected Iterable<KeyPair> getDefaultKeys(@NonNull File sshDir) {
        return List.of(identity);
    }

    @Override
    protected String getDefaultPreferredAuthentications() {
        return "publickey,password";
    }

    @Override
    @NonNull
    protected ServerKeyDatabase getServerKeyDatabase(@NonNull File homeDir,
                                                     @NonNull File sshDir) {
        return new ServerKeyDatabase() {
            @Override
            public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress, Configuration config) {
                return expectedServerPublicKeys;
            }

            @Override
            public boolean accept(String connectAddress, InetSocketAddress remoteAddress, PublicKey serverKey, Configuration config, CredentialsProvider provider) {

                for (PublicKey expectedServerPublicKey: expectedServerPublicKeys) {
                    if (KeyUtils.compareKeys(serverKey, expectedServerPublicKey))
                        return true;
                }
                log.warn("Remote key does not match local key remote/trusted: {} with {}", serverKey, expectedServerPublicKeys);
                return false;
            }
        };

    }
}
