package pro.deta.orion.auth;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;

import java.util.List;
import java.util.Optional;

/**
 * Authenticated subject; an absent organization id denotes the system scope. Organization authorization
 * reads the current organization once per decision, while system authorization uses authenticated ACL grants.
 */
public interface UserIdentity {
    String getUserId();

    boolean isAnonymous();

    Optional<OrganizationId> getOrganizationId();

    List<AccessControl.Grant> getGrants();

    default Optional<OrionDocument.Organization> currentOrganization() {
        return Optional.empty();
    }
}
