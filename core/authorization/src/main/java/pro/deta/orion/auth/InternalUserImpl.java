package pro.deta.orion.auth;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.ToString;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Request identities retain resolved grants. SSH session identities keep a user id and resolver,
 * producing a current snapshot when a new operation starts. Organization identities read desired state.
 */
@ToString
@Getter
public class InternalUserImpl implements UserIdentity {
    private final String clientId;
    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    private final Supplier<OrionDocument> configuration;
    private final Optional<OrganizationId> organizationId;
    @Getter(AccessLevel.NONE)
    private final List<Grant> grants;
    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    private final Supplier<UserIdentity> resolver;

    public InternalUserImpl(String userId, List<Grant> grants) {
        this.clientId = userId;
        this.organizationId = Optional.empty();
        this.grants = grants;
        this.configuration = null;
        this.resolver = null;
    }

    public InternalUserImpl(String userId, Supplier<UserIdentity> resolver) {
        this.clientId = userId;
        this.organizationId = Optional.empty();
        this.grants = List.of();
        this.configuration = null;
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public InternalUserImpl(String userId, OrganizationId organizationId, Supplier<OrionDocument> configuration) {
        this.clientId = userId;
        this.organizationId = Optional.of(organizationId);
        this.grants = List.of();
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.resolver = null;
    }

    @Override
    public List<Grant> getGrants() {
        return resolver == null ? grants : snapshot().getGrants();
    }

    @Override
    public UserIdentity snapshot() {
        if (resolver != null) return Objects.requireNonNull(resolver.get(), "resolved identity");
        return this;
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
