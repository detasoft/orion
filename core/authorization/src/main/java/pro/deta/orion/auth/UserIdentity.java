package pro.deta.orion.auth;

import pro.deta.orion.schema.acl.AccessControl;

import java.util.List;

public interface UserIdentity {
    String getUserId();

    boolean isAnonymous();

    List<AccessControl.Grant> getGrants();
}
