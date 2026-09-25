package pro.deta.orion.test.integration;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.security.PublicKey;
import java.util.List;

/** Provides direct root key enrollment and token issue for integration tests without SSH. */
public final class OrionTestRootAccess {
    private OrionTestRootAccess() {
    }

    @TestOnly
    public static void enroll(OrionAccessControlService accessControl, PublicKey key) {
        accessControl.addSshKeysToUser("root", List.of(PublicKeyEntry.toString(key)));
        if (!(accessControl.authenticateSshUser("root", key.getEncoded())
                instanceof AuthenticationResult.Success)) {
            throw new IllegalStateException("Enrolled test root key was not accepted");
        }
    }

    @TestOnly
    public static String issueToken(
            OrionAccessControlService accessControl, PublicKey key, long expiresInSeconds) {
        AuthenticationResult authentication = accessControl.authenticateSshUser("root", key.getEncoded());
        if (!(authentication instanceof AuthenticationResult.Success success)) {
            throw new IllegalStateException("Test root key authentication failed");
        }
        TokenRefreshResult result = accessControl.refreshToken(success, expiresInSeconds);
        if (!(result instanceof TokenRefreshResult.Success token)) {
            throw new IllegalStateException("Test root token issue failed");
        }
        return token.token();
    }
}
