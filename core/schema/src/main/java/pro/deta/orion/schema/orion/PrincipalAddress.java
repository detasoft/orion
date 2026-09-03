package pro.deta.orion.schema.orion;

import java.util.Objects;

public sealed interface PrincipalAddress
        permits PrincipalAddress.SystemPrincipalAddress, PrincipalAddress.OrganizationPrincipalAddress {
    String SYSTEM_SCOPE = "system";

    static PrincipalAddress parse(String value) {
        Objects.requireNonNull(value, "principal address");
        String[] segments = value.split("/", -1);
        if (segments.length != 2) {
            throw new IllegalArgumentException("principal address must have two segments: " + value);
        }
        UserId userId = new UserId(segments[1]);
        if (SYSTEM_SCOPE.equals(segments[0])) {
            return new SystemPrincipalAddress(userId);
        }
        return new OrganizationPrincipalAddress(new OrganizationId(segments[0]), userId);
    }

    UserId userId();

    boolean isSystem();

    default PrincipalAddress requireOrganization(OrganizationId requiredOrganizationId) {
        Objects.requireNonNull(requiredOrganizationId, "requiredOrganizationId");
        if (this instanceof OrganizationPrincipalAddress organizationPrincipal
                && organizationPrincipal.organizationId().equals(requiredOrganizationId)) {
            return this;
        }
        throw new IllegalArgumentException(
                "principal " + this + " does not belong to organization " + requiredOrganizationId);
    }

    record SystemPrincipalAddress(UserId userId) implements PrincipalAddress {
        public SystemPrincipalAddress {
            Objects.requireNonNull(userId, "userId");
        }

        @Override
        public boolean isSystem() {
            return true;
        }

        @Override
        public String toString() {
            return SYSTEM_SCOPE + "/" + userId;
        }
    }

    record OrganizationPrincipalAddress(OrganizationId organizationId, UserId userId)
            implements PrincipalAddress {
        public OrganizationPrincipalAddress {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(userId, "userId");
        }

        @Override
        public boolean isSystem() {
            return false;
        }

        @Override
        public String toString() {
            return organizationId + "/" + userId;
        }
    }
}
