package pro.deta.orion;

import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.acl.AccessControlAccess;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;

public interface OrionAccessControlService {
    long MAX_TOKEN_EXPIRES_IN_SECONDS = 3_600;

    default SshCredentialListResult listSshCredentials(String userId) {
        return SshCredentialListResult.failure(
                SshCredentialFailureCode.PERSISTENCE_FAILED,
                "SSH credential listing is unavailable");
    }

    boolean userExists(String userName);

    AuthenticationResult authenticateUser(String userName, byte[] credential);

    default SshKeyEnrollmentAuthentication authenticateSshKeyEnrollment(
            String userName,
            byte[] credential) {
        return SshKeyEnrollmentAuthentication.failure("authentication failed");
    }

    AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey);

    AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey);

    TokenAuthenticationResult verifyToken(byte[] token);

    TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds);

    TokenRefreshResult refreshToken(
            AuthenticationResult.Success renewalAuthority,
            long expiresInSeconds);

    AccessControlAccess createAccess(OrionRevision revision, ChangeMetadata metadata);

}
