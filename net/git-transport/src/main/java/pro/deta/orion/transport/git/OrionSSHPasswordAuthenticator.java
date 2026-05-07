package pro.deta.orion.transport.git;

import jakarta.inject.Inject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.server.auth.AsyncAuthException;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.password.PasswordChangeRequiredException;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AuthenticationResult;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;

import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Data
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OrionSSHPasswordAuthenticator implements PasswordAuthenticator, PublickeyAuthenticator {
    private final OrionAccessControlService orionAccessControlService;

    @Override
    public boolean authenticate(String username, String password, ServerSession session) throws PasswordChangeRequiredException, AsyncAuthException {
        return internalAuthenticate(username, password.getBytes(StandardCharsets.UTF_8), AccessControl.CredentialType.ARGON2, session);
    }

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) throws AsyncAuthException {
        return internalAuthenticate(username, key.getEncoded(), AccessControl.CredentialType.OPENSSH_PUBLIC_KEY, session);
    }

    private boolean internalAuthenticate(String username, byte[] encodedData, AccessControl.CredentialType credentialType, ServerSession session) {
        AuthenticationResult user = orionAccessControlService.authenticateUser(username, credentialType, encodedData);
        return switch (user) {
            case AuthenticationResult.Success(var u) -> {
                log.trace("SSH user '{}' logged in.", username);
                session.setAttribute(SSH_AUTHENTICATED_USER, u);
                yield true;
            }
            case AuthenticationResult.Failure(var reason, var throwable) -> {
                log.trace("SSH user '{}' denied: {}.", username, reason, throwable);
                yield false;
            }
        };
    }
}
