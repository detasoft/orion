package pro.deta.orion.auth;

import pro.deta.orion.acl.schema.AccessControl;

import java.util.List;

public interface UserIdentity {
    String getUserId();

    boolean isAnonymous();

    List<AccessControl.Grant> getGrants();
}
