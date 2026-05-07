package pro.deta.orion;

import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AuthenticationResult;


public interface OrionAccessControlService {
    void addKeyToUser(String username, String publicKey);

    AuthenticationResult authenticateUser(String userName, AccessControl.CredentialType credentialType, byte[] publicKey);
}
