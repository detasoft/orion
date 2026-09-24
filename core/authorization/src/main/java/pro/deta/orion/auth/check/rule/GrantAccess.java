package pro.deta.orion.auth.check.rule;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.ScopedAccess;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RepositoryAddress;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.UserId;

import java.util.List;
import java.util.Optional;

import static pro.deta.orion.auth.check.MatcherUtils.filterGrants;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

/**
 * Shared grant matching for system ACLs and organization snapshots. Organization scopes supply resource
 * ownership, while grant expressions select operations and narrow repository or branch access. A branch
 * decision resolves the current organization once and applies parent and branch constraints together.
 */
final class GrantAccess {
    private GrantAccess() {
    }

    static GrantMatcher repositoryGrant(String repositoryName) {
        return GrantMatcher.of(AccessControl.GrantKey.REPOSITORY, value -> matchExpressionValue(value, repositoryName));
    }

    static boolean hasGrant(UserIdentity userIdentity, GrantMatcher... matchers) {
        return !matchingGrants(userIdentity, matchers).isEmpty();
    }

    static List<AccessControl.Grant> matchingGrants(UserIdentity userIdentity, GrantMatcher... matchers) {
        if (userIdentity == null) {
            return List.of();
        }
        return filterGrants(userIdentity.getGrants(), matchers);
    }

    static AccessDecision scopedRepositoryAccess(UserIdentity identity, RepositoryResource repository,
            AccessControl.GrantKey action, String branch) {
        RepositoryAddress address;
        try {
            address = RepositoryAddress.parse(repository.repositoryName());
        } catch (IllegalArgumentException invalidAddress) {
            return AccessDecision.deny("organization repository address is required");
        }
        if (!identity.getOrganizationId().orElseThrow().equals(address.organizationId())) {
            return AccessDecision.deny("repository belongs to another scope");
        }
        Optional<OrionDocument.Organization> organization = identity.currentOrganization();
        if (organization.isEmpty()) return AccessDecision.deny("organization is unavailable");
        List<ScopedAccess.AssignedGrant> grants = ScopedAccess.assignedGrants(organization.orElseThrow(),
                new UserId(identity.getUserId()), ConfigurationScope.repository(address),
                action == AccessControl.GrantKey.CREATE);
        boolean restricted = false;
        if (branch != null) {
            for (ScopedAccess.AssignedGrant grant : grants) {
                if (grant.effect() == ScopedGrant.Effect.ALLOW
                        && matchesRepositoryAction(grant.expressions(), repository.repositoryName(), action)
                        && hasKey(grant.expressions(), AccessControl.GrantKey.BRANCH)) {
                    restricted = true;
                }
            }
        }
        boolean allowed = false;
        for (ScopedAccess.AssignedGrant grant : grants) {
            List<AccessControl.GrantExpression> expressions = grant.expressions();
            if (!matchesRepositoryAction(expressions, repository.repositoryName(), action)) continue;
            boolean branchRestricted = hasKey(expressions, AccessControl.GrantKey.BRANCH);
            if (grant.effect() == ScopedGrant.Effect.DENY) {
                if (!branchRestricted || branch != null && matchesBranch(expressions, branch)) {
                    return AccessDecision.deny("scoped deny grant matched");
                }
            } else if (branch == null || !restricted || matchesBranch(expressions, branch)) {
                allowed = true;
            }
        }
        return allowed ? AccessDecision.allow("scoped repository grant matched")
                : AccessDecision.deny("missing scoped repository grant");
    }

    private static boolean matchesRepositoryAction(List<AccessControl.GrantExpression> expressions,
            String repositoryName, AccessControl.GrantKey action) {
        if (hasKey(expressions, AccessControl.GrantKey.NETWORK_SOURCE)
                || hasKey(expressions, AccessControl.GrantKey.NETWORK_PORT)) return false;
        boolean repositoryRestricted = hasKey(expressions, AccessControl.GrantKey.REPOSITORY);
        if (repositoryRestricted && !repositoryGrant(repositoryName).matchesAny(expressions)) return false;
        if (action == AccessControl.GrantKey.READ) {
            if (hasKey(expressions, AccessControl.GrantKey.READ)
                    || hasKey(expressions, AccessControl.GrantKey.READ_WRITE)) return true;
            return repositoryRestricted && !hasKey(expressions, AccessControl.GrantKey.CREATE)
                    && !hasKey(expressions, AccessControl.GrantKey.FORCE)
                    && !hasKey(expressions, AccessControl.GrantKey.ADMIN)
                    && !hasKey(expressions, AccessControl.GrantKey.SHUTDOWN);
        }
        return hasKey(expressions, action);
    }

    private static boolean matchesBranch(List<AccessControl.GrantExpression> expressions, String branch) {
        return GrantMatcher.of(AccessControl.GrantKey.BRANCH,
                value -> "*".equals(value) || branch.equals(value)).matchesAny(expressions);
    }

    private static boolean hasKey(List<AccessControl.GrantExpression> expressions, AccessControl.GrantKey key) {
        return GrantMatcher.of(key).matchesAny(expressions);
    }

    static List<AccessControl.Grant> branchRestrictedRepositoryGrants(UserIdentity userIdentity, String repositoryName) {
        return matchingGrants(
                userIdentity,
                repositoryGrant(repositoryName),
                GrantMatcher.of(AccessControl.GrantKey.BRANCH));
    }
}
