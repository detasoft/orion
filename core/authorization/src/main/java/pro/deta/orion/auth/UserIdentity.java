package pro.deta.orion.auth;

import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.util.List;
import java.util.Optional;

/**
 * Authenticated subject; an absent organization id denotes the system scope. Organization authorization
 * reads the current organization once per decision. SSH subjects resolve a current identity snapshot
 * when a command or Git operation starts; request identities retain that snapshot for the operation.
 */
public interface UserIdentity {
    String getUserId();

    boolean isAnonymous();

    Optional<OrganizationId> getOrganizationId();

    List<Grant> getGrants();

    default UserIdentity snapshot() {
        return this;
    }

    default Optional<OrionDocument.Organization> currentOrganization() {
        return Optional.empty();
    }
}
