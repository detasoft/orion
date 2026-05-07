package pro.deta.orion.auth.check.rule;

import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.AccessRule;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.resource.BranchResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;

import java.util.List;
import java.util.Objects;

import static pro.deta.orion.auth.check.MatcherUtils.filterGrants;

/**
 * Rules for branch-level access. A branch is evaluated through its parent repository first, so a matching
 * branch expression cannot bypass the repository expression in the same ACL grant.
 */
public final class BranchAccessRules {
    private static final AccessRule<BranchResource> FETCH =
            new NamedAccessRule<>("branch fetch", BranchAccessRules::evaluateFetch);

    private BranchAccessRules() {
    }

    public static AccessRule<BranchResource> fetch() {
        return FETCH;
    }

    private static AccessDecision evaluateFetch(SecurityContext securityContext, BranchResource resource) {
        RepositoryResource repository = resource.parentResource();
        AccessDecision repositoryRead = RepositoryAccessRules.read().evaluate(securityContext, repository);
        if (!repositoryRead.allowed()) {
            return AccessDecision.deny("parent repository read denied: " + repositoryRead.reason());
        }

        List<AccessControl.Grant> branchGrants = GrantAccess.branchRestrictedRepositoryGrants(
                securityContext.getUserIdentity(),
                repository.repositoryName());
        if (branchGrants.isEmpty()) {
            return AccessDecision.allow("parent repository grant has no branch restriction");
        }
        if (hasWildcardBranchGrant(branchGrants)) {
            return AccessDecision.allow("wildcard branch grant matched");
        }
        if (isBranchAllowed(branchGrants, resource.branchName())) {
            return AccessDecision.allow("branch grant matched");
        }
        return AccessDecision.deny("missing branch grant for " + resource.branchName());
    }

    private static boolean hasWildcardBranchGrant(List<AccessControl.Grant> branchGrants) {
        return !filterGrants(branchGrants, GrantMatcher.of(AccessControl.GrantKey.BRANCH, "*"::equalsIgnoreCase)).isEmpty();
    }

    private static boolean isBranchAllowed(List<AccessControl.Grant> branchGrants, String branchName) {
        return !filterGrants(
                branchGrants,
                GrantMatcher.of(AccessControl.GrantKey.BRANCH, grantBranchName -> Objects.equals(grantBranchName, branchName)))
                .isEmpty();
    }
}
