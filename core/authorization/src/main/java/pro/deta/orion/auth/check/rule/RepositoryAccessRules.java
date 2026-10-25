package pro.deta.orion.auth.check.rule;

import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.AccessRule;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.resource.RepositoryResource;

import java.util.List;

import static pro.deta.orion.auth.check.MatcherUtils.filterGrants;

/**
 * Rules for repository-wide grants. These rules know how ACL repository expressions map to repository names,
 * but they do not know anything about git protocol transport details.
 * Unrestricted operations require access to every branch, including branches not present in the current refs.
 * Write and force restrictions are evaluated independently from grants for other operations.
 */
public final class RepositoryAccessRules {
    private static final AccessRule<RepositoryResource> CREATE = new NamedAccessRule<>("repository create", RepositoryAccessRules::evaluateCreate);
    private static final AccessRule<RepositoryResource> READ = new NamedAccessRule<>("repository read", RepositoryAccessRules::evaluateRead);
    private static final AccessRule<RepositoryResource> WRITE = new NamedAccessRule<>("repository write", RepositoryAccessRules::evaluateWrite);
    private static final AccessRule<RepositoryResource> FORCE = new NamedAccessRule<>("repository force", RepositoryAccessRules::evaluateForce);
    private static final AccessRule<RepositoryResource> READ_UNRESTRICTED =
            new NamedAccessRule<>("unrestricted repository read",
                    (context, resource) -> evaluateUnrestricted(context, resource, GrantKey.READ, READ));
    private static final AccessRule<RepositoryResource> WRITE_UNRESTRICTED =
            new NamedAccessRule<>("unrestricted repository write",
                    (context, resource) -> evaluateUnrestricted(context, resource, GrantKey.READ_WRITE, WRITE));
    private static final AccessRule<RepositoryResource> FORCE_UNRESTRICTED =
            new NamedAccessRule<>("unrestricted repository force",
                    (context, resource) -> evaluateUnrestricted(context, resource, GrantKey.FORCE, FORCE));

    private RepositoryAccessRules() {
    }

    public static AccessRule<RepositoryResource> create() {
        return CREATE;
    }

    public static AccessRule<RepositoryResource> read() {
        return READ;
    }

    public static AccessRule<RepositoryResource> readUnrestricted() {
        return READ_UNRESTRICTED;
    }

    public static AccessRule<RepositoryResource> write() {
        return WRITE;
    }

    public static AccessRule<RepositoryResource> writeUnrestricted() {
        return WRITE_UNRESTRICTED;
    }

    public static AccessRule<RepositoryResource> force() {
        return FORCE;
    }

    public static AccessRule<RepositoryResource> forceUnrestricted() {
        return FORCE_UNRESTRICTED;
    }

    private static AccessDecision evaluateCreate(SecurityContext securityContext, RepositoryResource resource) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        if (userIdentity.getOrganizationId().isPresent()) {
            return GrantAccess.scopedRepositoryAccess(userIdentity, resource, GrantKey.CREATE, null, false);
        }
        boolean allowed = GrantAccess.hasGrant(
                userIdentity,
                GrantAccess.repositoryGrant(resource.repositoryName()),
                GrantMatcher.enabled(GrantKey.CREATE));
        if (allowed) {
            return AccessDecision.allow("repository create grant matched");
        }
        return AccessDecision.deny("missing repository create grant for " + resource.repositoryName());
    }

    private static AccessDecision evaluateRead(SecurityContext securityContext, RepositoryResource resource) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        if (userIdentity.getOrganizationId().isPresent()) {
            return GrantAccess.scopedRepositoryAccess(userIdentity, resource, GrantKey.READ, null, false);
        }
        boolean allowed = GrantAccess.hasGrant(userIdentity, GrantAccess.repositoryGrant(resource.repositoryName()));
        if (allowed) {
            return AccessDecision.allow("repository grant matched");
        }
        return AccessDecision.deny("missing repository grant for " + resource.repositoryName());
    }

    private static AccessDecision evaluateUnrestricted(SecurityContext securityContext,
            RepositoryResource resource, GrantKey action, AccessRule<RepositoryResource> parentRule) {
        UserIdentity identity = securityContext.getUserIdentity();
        if (identity.getOrganizationId().isPresent()) {
            return GrantAccess.scopedRepositoryAccess(identity, resource, action, null, true);
        }
        AccessDecision parent = parentRule.evaluate(securityContext, resource);
        if (!parent.allowed()) return parent;
        List<Grant> restricted = GrantAccess.branchRestrictedRepositoryGrants(
                identity, resource.repositoryName());
        if (action != GrantKey.READ) {
            restricted = filterGrants(restricted, GrantMatcher.enabled(action));
        }
        if (restricted.isEmpty()) return parent;
        for (Grant grant : restricted) {
            if (GrantMatcher.of(GrantKey.BRANCH, "*"::equals).matchesAny(grant.info())) return parent;
        }
        return AccessDecision.deny("repository access is restricted to specific branches");
    }

    private static AccessDecision evaluateWrite(SecurityContext securityContext, RepositoryResource resource) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        if (userIdentity.getOrganizationId().isPresent()) {
            return GrantAccess.scopedRepositoryAccess(userIdentity, resource, GrantKey.READ_WRITE, null, false);
        }
        boolean allowed = GrantAccess.hasGrant(
                userIdentity,
                GrantAccess.repositoryGrant(resource.repositoryName()),
                GrantMatcher.enabled(GrantKey.READ_WRITE));
        if (allowed) {
            return AccessDecision.allow("repository write grant matched");
        }
        return AccessDecision.deny("missing repository write grant for " + resource.repositoryName());
    }

    private static AccessDecision evaluateForce(SecurityContext securityContext, RepositoryResource resource) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        if (userIdentity.getOrganizationId().isPresent()) {
            return GrantAccess.scopedRepositoryAccess(userIdentity, resource, GrantKey.FORCE, null, false);
        }
        boolean allowed = GrantAccess.hasGrant(
                userIdentity,
                GrantAccess.repositoryGrant(resource.repositoryName()),
                GrantMatcher.enabled(GrantKey.FORCE));
        if (allowed) {
            return AccessDecision.allow("repository force grant matched");
        }
        return AccessDecision.deny("missing repository force grant for " + resource.repositoryName());
    }
}
