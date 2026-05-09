package pro.deta.orion;

import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;


public interface OrionAccessControlService {
    void addKeyToUser(String username, String publicKey);

    void createOrUpdateUser(AccessControlUserUpdate userUpdate);

    AuthenticationResult authenticateUser(String userName, byte[] credential);
}
