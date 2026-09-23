package pro.deta.orion.auth;

import lombok.Getter;
import lombok.ToString;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrganizationId;

import java.util.List;
import java.util.Optional;
import java.util.Objects;

@ToString
@Getter
public class InternalUserImpl implements UserIdentity {
    private final String clientId;
    private final Optional<OrganizationId> organizationId;
    private final List<AccessControl.Grant> grants;

    public InternalUserImpl(String userId, List<AccessControl.Grant> grants) {
        this(userId, grants, Optional.empty());
    }

    public InternalUserImpl(String userId, List<AccessControl.Grant> grants,
            Optional<OrganizationId> organizationId) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
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
