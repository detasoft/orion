package pro.deta.orion.auth.check.rule;

import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.AccessRule;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.resource.RepositoryResource;

/**
 * Rules for repository-wide grants. These rules know how ACL repository expressions map to repository names,
 * but they do not know anything about git protocol transport details.
 */
public final class RepositoryAccessRules {
    private static final AccessRule<RepositoryResource> CREATE = new NamedAccessRule<>("repository create", RepositoryAccessRules::evaluateCreate);
    private static final AccessRule<RepositoryResource> READ = new NamedAccessRule<>("repository read", RepositoryAccessRules::evaluateRead);
    private static final AccessRule<RepositoryResource> WRITE = new NamedAccessRule<>("repository write", RepositoryAccessRules::evaluateWrite);

    private RepositoryAccessRules() {
    }

    public static AccessRule<RepositoryResource> create() {
        return CREATE;
    }

    public static AccessRule<RepositoryResource> read() {
        return READ;
    }

    public static AccessRule<RepositoryResource> write() {
        return WRITE;
    }

    private static AccessDecision evaluateCreate(SecurityContext securityContext, RepositoryResource resource) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        boolean allowed = GrantAccess.hasGrant(
                userIdentity,
                GrantAccess.repositoryGrant(resource.repositoryName()),
                GrantMatcher.of(AccessControl.GrantKey.CREATE));
        if (allowed) {
            return AccessDecision.allow("repository create grant matched");
        }
        return AccessDecision.deny("missing repository create grant for " + resource.repositoryName());
    }

    private static AccessDecision evaluateRead(SecurityContext securityContext, RepositoryResource resource) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        boolean allowed = GrantAccess.hasGrant(userIdentity, GrantAccess.repositoryGrant(resource.repositoryName()));
        if (allowed) {
            return AccessDecision.allow("repository grant matched");
        }
        return AccessDecision.deny("missing repository grant for " + resource.repositoryName());
    }

    private static AccessDecision evaluateWrite(SecurityContext securityContext, RepositoryResource resource) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        boolean allowed = GrantAccess.hasGrant(
                userIdentity,
                GrantAccess.repositoryGrant(resource.repositoryName()),
                GrantMatcher.of(AccessControl.GrantKey.WRITE));
        if (allowed) {
            return AccessDecision.allow("repository write grant matched");
        }
        return AccessDecision.deny("missing repository write grant for " + resource.repositoryName());
    }
}
