package pro.deta.orion.acl.storage;

import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.util.Result;

import java.util.Optional;

public interface AccessControlStorage {
    Result<AccessControl> loadAccessControl();

    void saveAccessControl(AccessControl accessControl, String message, UserEmail author);

}
