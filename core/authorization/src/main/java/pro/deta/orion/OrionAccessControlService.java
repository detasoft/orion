package pro.deta.orion;

import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.SshKeyEnrollmentResult;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;

import java.util.List;

public interface OrionAccessControlService {
    void addKeyToUser(String username, String publicKey);

    void addSshKeysToUser(String username, List<String> publicKeys);

    default SshCredentialListResult listSshCredentials(String userId) {
        return SshCredentialListResult.failure(
                SshCredentialFailureCode.PERSISTENCE_FAILED,
                "SSH credential listing is unavailable");
    }

    default SshCredentialUpdateResult addSshCredentials(String userId, List<String> publicKeys) {
        return SshCredentialUpdateResult.failure(
                SshCredentialFailureCode.PERSISTENCE_FAILED,
                "SSH credential addition is unavailable");
    }

    default SshCredentialUpdateResult removeSshCredential(
            String userId,
            String fingerprintPrefix,
            boolean force) {
        return SshCredentialUpdateResult.failure(
                SshCredentialFailureCode.PERSISTENCE_FAILED,
                "SSH credential removal is unavailable");
    }

    void createOrUpdateUser(AccessControlUserUpdate userUpdate);

    boolean userExists(String userName);

    AuthenticationResult authenticateUser(String userName, byte[] credential);

    default SshKeyEnrollmentAuthentication authenticateSshKeyEnrollment(
            String userName,
            byte[] credential) {
        return SshKeyEnrollmentAuthentication.failure("authentication failed");
    }

    default SshKeyEnrollmentResult completeRootSshKeyEnrollment(
            String expectedGeneration,
            List<String> publicKeys) {
        return SshKeyEnrollmentResult.failure("key enrollment failed");
    }

    AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey);

    AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey);

    AuthenticationResult authenticateToken(byte[] token);

    TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds);

    TokenIssueResult issueTokenFor(UserIdentity userIdentity, long expiresInSeconds);

    byte[] accessControlConfigurationFile();

    void saveAccessControlConfigurationFile(byte[] content);
}
