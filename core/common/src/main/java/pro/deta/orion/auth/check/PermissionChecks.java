package pro.deta.orion.auth.check;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.*;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;

import static pro.deta.orion.auth.check.MatcherUtils.filterGrants;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

@Slf4j
public class PermissionChecks {
    private static final PermissionChecks INSTANCE = new PermissionChecks();
    private static final String REPOSITORY_CREATE_CHECK = "repository create";
    private static final String REPOSITORY_FETCH_CHECK = "repository fetch";
    private static final String REPOSITORY_READ_CHECK = "repository read";
    private static final String REPOSITORY_WRITE_CHECK = "repository write";
    private static final String APPLICATION_SHUTDOWN_CHECK = "application shutdown";
    private static final String LOCAL_CONNECTION_CHECK = "local connection";
    private static final String AUTHENTICATED_USER_CHECK = "authenticated user";

    public void requireRepositoryCreate(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        boolean allowed = hasGrant(userIdentity, repositoryGrant(repositoryName), GrantMatcher.of(AccessControl.GrantKey.CREATE));
        if (allowed) {
            log.debug("Repository create check passed for {} on {}", userIdentity, repositoryName);
        }
        requireAllowed(securityContext, REPOSITORY_CREATE_CHECK, allowed);
    }

    public void requireRepositoryFetch(SecurityContext securityContext, GitFetchAccessRequest request) throws OrionSecurityException {
        List<AccessControl.Grant> branchGrants = branchRestrictedRepositoryGrants(securityContext.getUserIdentity(), request.repositoryName());
        if (branchGrants.isEmpty()) {
            requireAllowed(securityContext, REPOSITORY_FETCH_CHECK, true);
            return;
        }
        if (hasWildcardBranchGrant(branchGrants)) {
            requireAllowed(securityContext, REPOSITORY_FETCH_CHECK, true);
            return;
        }

        Map<GitObjectId, String> wantedBranches = resolveWantedBranches(request);
        boolean allowed = allWantsWereResolved(request, wantedBranches)
                && allWantedBranchesAreAllowed(request, branchGrants, wantedBranches);
        requireAllowed(securityContext, REPOSITORY_FETCH_CHECK, allowed);
    }

    public void requireRepositoryRead(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        boolean allowed = hasGrant(userIdentity, repositoryGrant(repositoryName));
        if (allowed) {
            log.debug("Repository read check passed for {} on {}", userIdentity, repositoryName);
        }
        requireAllowed(securityContext, REPOSITORY_READ_CHECK, allowed);
    }

    public void requireRepositoryWrite(SecurityContext securityContext, String repositoryName) throws OrionSecurityException {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        boolean allowed = hasGrant(userIdentity, repositoryGrant(repositoryName), GrantMatcher.of(AccessControl.GrantKey.WRITE));
        if (allowed) {
            log.debug("Repository write check passed for {} on {}", userIdentity, repositoryName);
        }
        requireAllowed(securityContext, REPOSITORY_WRITE_CHECK, allowed);
    }

    public void requireApplicationShutdown(SecurityContext securityContext) throws OrionSecurityException {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        boolean allowed = hasGrant(userIdentity, GrantMatcher.of(AccessControl.GrantKey.SHUTDOWN));
        if (allowed) {
            log.debug("Application shutdown check passed for {}", userIdentity);
        }
        requireAllowed(securityContext, APPLICATION_SHUTDOWN_CHECK, allowed);
    }

    public void requireLocalConnection(SecurityContext securityContext, SocketAddress socketAddress) throws OrionSecurityException {
        boolean allowed = socketAddress instanceof InetSocketAddress inetSocketAddress
                && inetSocketAddress.getAddress().isLoopbackAddress();
        requireAllowed(securityContext, LOCAL_CONNECTION_CHECK, allowed);
    }

    public void requireAuthenticatedUser(SecurityContext securityContext) throws OrionSecurityException {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        requireAllowed(securityContext, AUTHENTICATED_USER_CHECK, userIdentity != null && !userIdentity.isAnonymous());
    }


    public static PermissionChecks permissionChecker() {
        return INSTANCE;
    }

    private static void requireAllowed(SecurityContext securityContext, String checkName, boolean allowed) throws OrionSecurityException {
        if (log.isTraceEnabled()) {
            log.atTrace().log("SC: {} result: {} by {}", securityContext, allowed, checkName);
        }
        if (!allowed) {
            log.atWarn().log("ACCESS DENIED ({}): SC: {}", checkName, securityContext);
            throw new OrionSecurityException("Origin: disallowed for [" + securityContext.formatShort() + "] by '" + checkName + "'");
        }
    }

    private static GrantMatcher repositoryGrant(String repositoryName) {
        return GrantMatcher.of(AccessControl.GrantKey.REPOSITORY, value -> matchExpressionValue(value, repositoryName));
    }

    private static boolean hasGrant(UserIdentity userIdentity, GrantMatcher... matchers) {
        return !matchingGrants(userIdentity, matchers).isEmpty();
    }

    private static List<AccessControl.Grant> matchingGrants(UserIdentity userIdentity, GrantMatcher... matchers) {
        return filterGrants(userIdentity.getGrants(), matchers);
    }

    private static List<AccessControl.Grant> branchRestrictedRepositoryGrants(UserIdentity userIdentity, String repositoryName) {
        return matchingGrants(
                userIdentity,
                repositoryGrant(repositoryName),
                GrantMatcher.of(AccessControl.GrantKey.BRANCH));
    }

    private static boolean hasWildcardBranchGrant(List<AccessControl.Grant> branchGrants) {
        return !filterGrants(branchGrants, GrantMatcher.of(AccessControl.GrantKey.BRANCH, "*"::equalsIgnoreCase)).isEmpty();
    }

    private static Map<GitObjectId, String> resolveWantedBranches(GitFetchAccessRequest request) {
        return request.refResolver().resolveBranchNames(request.wants());
    }

    private static boolean allWantsWereResolved(GitFetchAccessRequest request, Map<GitObjectId, String> wantedBranches) {
        for (GitObjectId want : request.wants()) {
            if (!wantedBranches.containsKey(want)) {
                log.error("Can't find commit {} in repository {}", want.value(), request.repositoryName());
                return false;
            }
        }
        return true;
    }

    private static boolean allWantedBranchesAreAllowed(
            GitFetchAccessRequest request,
            List<AccessControl.Grant> branchGrants,
            Map<GitObjectId, String> wantedBranches) {
        for (GitObjectId want : request.wants()) {
            String branchName = wantedBranches.get(want);
            if (!isBranchAllowed(branchGrants, branchName)) {
                log.warn("Fetch denied for {} from repository {} because branch {} is not allowed",
                        want.value(),
                        request.repositoryName(),
                        branchName);
                return false;
            }
        }
        return true;
    }

    private static boolean isBranchAllowed(List<AccessControl.Grant> branchGrants, String branchName) {
        return !filterGrants(
                branchGrants,
                GrantMatcher.of(AccessControl.GrantKey.BRANCH, grantBranchName -> Objects.equals(grantBranchName, branchName)))
                .isEmpty();
    }
}
