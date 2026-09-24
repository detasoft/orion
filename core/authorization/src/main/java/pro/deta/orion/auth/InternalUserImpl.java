package pro.deta.orion.auth;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.ToString;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

/** System identities retain authenticated ACL grants; organization identities read current desired state. */
@ToString
@Getter
public class InternalUserImpl implements UserIdentity {
    private final String clientId;
    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    private final Supplier<OrionDocument> configuration;
    private final Optional<OrganizationId> organizationId;
    private final List<AccessControl.Grant> grants;

    public InternalUserImpl(String userId, List<AccessControl.Grant> grants) {
        this.clientId = userId;
        this.organizationId = Optional.empty();
        this.grants = grants;
        this.configuration = null;
    }

    public InternalUserImpl(String userId, OrganizationId organizationId, Supplier<OrionDocument> configuration) {
        this.clientId = userId;
        this.organizationId = Optional.of(organizationId);
        this.grants = List.of();
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    public Optional<OrionDocument.Organization> currentOrganization() {
        if (configuration == null) return Optional.empty();
        for (OrionDocument.Organization organization : configuration.get().organizations()) {
            if (organizationId.orElseThrow().equals(organization.id())) return Optional.of(organization);
        }
        return Optional.empty();
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
