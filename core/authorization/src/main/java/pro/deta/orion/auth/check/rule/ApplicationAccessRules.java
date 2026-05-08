package pro.deta.orion.auth.check.rule;

import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.AccessRule;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.ApplicationShutdownResource;

/**
 * Rules for operations that affect the whole Orion process rather than a repository or transport session.
 */
public final class ApplicationAccessRules {
    private static final AccessRule<ApplicationShutdownResource> SHUTDOWN =
            new NamedAccessRule<>("application shutdown", ApplicationAccessRules::evaluateShutdown);
    private static final AccessRule<ApplicationAdminResource> ADMIN =
            new NamedAccessRule<>("application admin", ApplicationAccessRules::evaluateAdmin);

    private ApplicationAccessRules() {
    }

    public static AccessRule<ApplicationShutdownResource> shutdown() {
        return SHUTDOWN;
    }

    public static AccessRule<ApplicationAdminResource> admin() {
        return ADMIN;
    }

    private static AccessDecision evaluateShutdown(SecurityContext securityContext, ApplicationShutdownResource resource) {
        boolean allowed = GrantAccess.hasGrant(securityContext.getUserIdentity(), GrantMatcher.of(AccessControl.GrantKey.SHUTDOWN));
        if (allowed) {
            return AccessDecision.allow("shutdown grant matched");
        }
        return AccessDecision.deny("missing application shutdown grant");
    }

    private static AccessDecision evaluateAdmin(SecurityContext securityContext, ApplicationAdminResource resource) {
        boolean allowed = GrantAccess.hasGrant(securityContext.getUserIdentity(), GrantMatcher.of(AccessControl.GrantKey.ADMIN));
        if (allowed) {
            return AccessDecision.allow("admin grant matched");
        }
        return AccessDecision.deny("missing application admin grant");
    }
}
