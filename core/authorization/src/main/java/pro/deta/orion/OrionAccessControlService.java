package pro.deta.orion;

import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;

import java.util.List;

public interface OrionAccessControlService {
    void addKeyToUser(String username, String publicKey);

    void addSshKeysToUser(String username, List<String> publicKeys);

    void createOrUpdateUser(AccessControlUserUpdate userUpdate);

    boolean userExists(String userName);

    AuthenticationResult authenticateUser(String userName, byte[] credential);

    AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey);

    AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey);

    AuthenticationResult authenticateToken(byte[] token);

    TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds);

    TokenIssueResult issueTokenFor(UserIdentity userIdentity, long expiresInSeconds);

    byte[] accessControlConfigurationFile();

    void saveAccessControlConfigurationFile(byte[] content);
}
