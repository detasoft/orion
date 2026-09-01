package pro.deta.orion.git.client;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.future.AuthFuture;

import java.io.IOException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Objects;

/**
 * Supplies credentials and authenticates one outbound Git SSH session.
 */
@FunctionalInterface
public interface GitSshSessionAuthenticator {
    void authenticate(ClientSession session, Duration timeout) throws IOException;

    static GitSshSessionAuthenticator defaultIdentities() {
        return (session, timeout) -> authenticate(session.auth(), timeout);
    }

    static GitSshSessionAuthenticator password(String password) {
        Objects.requireNonNull(password, "password");
        return (session, timeout) -> {
            session.addPasswordIdentity(password);
            authenticate(session.auth(), timeout);
        };
    }

    static GitSshSessionAuthenticator publicKey(KeyPair keyPair) {
        Objects.requireNonNull(keyPair, "keyPair");
        return (session, timeout) -> {
            session.addPublicKeyIdentity(keyPair);
            authenticate(session.auth(), timeout);
        };
    }

    private static void authenticate(AuthFuture authentication, Duration timeout)
            throws IOException {
        if (!authentication.await(timeout)) {
            authentication.cancel();
            throw new GitSshAuthenticationTimeoutException();
        }
        authentication.verify();
    }
}

final class GitSshAuthenticationTimeoutException extends IOException {
}
