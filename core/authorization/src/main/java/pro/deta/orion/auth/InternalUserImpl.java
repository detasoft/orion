package pro.deta.orion.auth;

import lombok.Getter;
import lombok.ToString;
import pro.deta.orion.acl.schema.AccessControl;

import java.util.List;

@ToString
@Getter
public class InternalUserImpl implements UserIdentity {
    private final String clientId;
    private final List<AccessControl.Grant> grants;

    public InternalUserImpl(String userId, List<AccessControl.Grant> grants) {
        this.clientId = userId;
        this.grants = grants;
    }

    @Override
    public String getUserId() {
        return clientId;
    }

    @Override
    public boolean isAnonymous() {
        return clientId == null;
    }
}
