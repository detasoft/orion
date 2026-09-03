package pro.deta.orion;

import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.SshKeyEnrollmentResult;

import java.util.List;

public interface OrionAccessControlService {
    void addKeyToUser(String username, String publicKey);

    void addSshKeysToUser(String username, List<String> publicKeys);

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
