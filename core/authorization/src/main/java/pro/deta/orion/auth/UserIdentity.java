package pro.deta.orion.auth;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrganizationId;

import java.util.List;
import java.util.Optional;

/** Authenticated subject; an absent organization denotes the system scope. */
public interface UserIdentity {
    String getUserId();

    boolean isAnonymous();

    Optional<OrganizationId> getOrganizationId();

    List<AccessControl.Grant> getGrants();
}
