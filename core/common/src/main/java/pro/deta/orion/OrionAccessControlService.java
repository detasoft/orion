package pro.deta.orion;

import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.util.Result;


public interface OrionAccessControlService extends OrionApplicationStageEventListener {
    void addKeyToUser(String username, String publicKey);

    Result<UserIdentity> authenticateUser(String userName, AccessControl.CredentialType credentialType, byte[] publicKey);
}
