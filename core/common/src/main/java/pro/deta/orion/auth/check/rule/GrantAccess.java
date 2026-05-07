package pro.deta.orion.auth.check.rule;

import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.GrantMatcher;

import java.util.List;

import static pro.deta.orion.auth.check.MatcherUtils.filterGrants;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

/**
 * Shared ACL grant helpers. Orion's ACL grants are stored as a flat list of expressions, so a branch
 * restriction is represented by a grant that contains both REPOSITORY and BRANCH expressions.
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

    static List<AccessControl.Grant> branchRestrictedRepositoryGrants(UserIdentity userIdentity, String repositoryName) {
        return matchingGrants(
                userIdentity,
                repositoryGrant(repositoryName),
                GrantMatcher.of(AccessControl.GrantKey.BRANCH));
    }
}
