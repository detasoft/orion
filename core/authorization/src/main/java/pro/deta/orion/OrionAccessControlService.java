package pro.deta.orion;

import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;


public interface OrionAccessControlService {
    void addKeyToUser(String username, String publicKey);

    void createOrUpdateUser(AccessControlUserUpdate userUpdate);

    AuthenticationResult authenticateUser(String userName, byte[] credential);

    AuthenticationResult authenticateToken(byte[] token);

    TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds);
}
