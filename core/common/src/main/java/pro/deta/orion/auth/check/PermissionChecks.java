package pro.deta.orion.auth.check;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.*;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;

import static pro.deta.orion.auth.Decision.ALLOW;
import static pro.deta.orion.auth.Decision.DENY;
import static pro.deta.orion.auth.Permission.*;
import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static pro.deta.orion.auth.check.MatcherUtils.filterGrants;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;

@Slf4j
public class PermissionChecks {
    private static final PermissionChecks _instance = new PermissionChecks();

    public final PermissionChecker<String> ALLOW_TO_CREATE_REPO = createFor(REPOSITORY_CREATE, "Allow create new repositories", (userIdentity, repositoryName) -> {
        boolean allowed = hasGrant(
                userIdentity,
                repositoryGrant(repositoryName),
                GrantMatcher.of(AccessControl.GrantKey.CREATE));
        if (allowed) {
            log.debug("Check ALLOW_TO_CREATE_REPO passed for {} on {}", userIdentity, repositoryName);
        }
        return allowIf(allowed);
    });

    public final PermissionChecker<GitFetchAccessRequest> ALLOW_TO_FETCH_REPO = createFor(REPOSITORY_FETCH, "Allow fetch from repository", (userIdentity, frsc) -> {
        List<AccessControl.Grant> branchGrants = branchRestrictedRepositoryGrants(userIdentity, frsc.repositoryName());
        if (branchGrants.isEmpty()) {
            return ALLOW;
        }
        if (hasWildcardBranchGrant(branchGrants)) {
            return ALLOW;
        }

        Map<GitObjectId, String> wantedBranches = resolveWantedBranches(frsc);
        if (!allWantsWereResolved(frsc, wantedBranches)) {
            return DENY;
        }
        return allowIf(hasAnyAllowedWantedBranch(branchGrants, wantedBranches));
    });

    public final PermissionChecker<String> ALLOW_READ_ACCESS = createFor(REPOSITORY_READ, "Allow read access", (userIdentity, repository) -> {
        boolean allowed = hasGrant(userIdentity, repositoryGrant(repository));
        if (allowed) {
            log.debug("Check ALLOW_READ_ACCESS passed for {} on {}", userIdentity, repository);
        }
        return allowIf(allowed);
    });

    public final PermissionChecker<String> ALLOW_WRITE_ACCESS = createFor(REPOSITORY_WRITE, "Allow write access", (userIdentity, repository) -> {
        boolean allowed = hasGrant(
                userIdentity,
                repositoryGrant(repository),
                GrantMatcher.of(AccessControl.GrantKey.WRITE));
        if (allowed) {
            log.debug("Check ALLOW_WRITE_ACCESS passed for {} on {}", userIdentity, repository);
        }
        return allowIf(allowed);
    });

    public final PermissionChecker<String> ALLOW_TO_SHUTDOWN = createFor(APPLICATION_SHUTDOWN, "Allow application shutdown", (userIdentity, command) -> {
        boolean allowed = hasGrant(userIdentity, GrantMatcher.of(AccessControl.GrantKey.SHUTDOWN));
        if (allowed) {
            log.debug("Check ALLOW_TO_SHUTDOWN passed for {}", userIdentity);
        }
        return allowIf(allowed);
    });

    public final PermissionChecker<SocketAddress> ALLOW_ONLY_LOCAL_CONNECTIONS = createFor(Permission.CLIENT_SOCKET_ADDRESS, "Allow only local connections", (userIdentity, socketAddress) -> {
        return allowIf(socketAddress instanceof InetSocketAddress inetSocketAddress
                && inetSocketAddress.getAddress().isLoopbackAddress());
    });

    public final PermissionChecker<UserIdentity> ALLOW_ANONYMOUS_ACCESS = createFor(Permission.USER_IDENTITY, "Allow anonymous access", (userIdentity, ui) -> {
        return allowIf(userIdentity != null && !userIdentity.isAnonymous());
    });


    public static PermissionChecks permissionChecker() {
        return _instance;
    }

    public <O> void permissionCheck(PermissionChecker<O> checker) throws OrionSecurityException {
        Decision decision = checker.validate();

        if (log.isTraceEnabled()) {
            log.atTrace().log("SC: {} result: {} by {}", getSc(), decision, checker.getName());
        }
        if (decision == DENY) {
            log.atWarn().log("ACCESS DENIED ({}): SC: {}", checker.getName(), getSc());
            throw new OrionSecurityException("Origin: disallowed for [" + getSc().formatShort() + "] by '" + checker.getName() + "'");
        }
    }

    public <O> PermissionChecker<O> createFor(Permission<O> permission, String name, Validator<O> func) {
        return new PermissionChecker<>(name, permission) {
            @Override
            public Decision validate() {
                return func.validate(getSc().getUserIdentity(), getSc().getAttribute(permission));
            }
        };
    }

    @FunctionalInterface
    public interface Validator<O> {
        Decision validate(UserIdentity ui, O object);

        default SecurityContext sc() {
            return getSc();
        }
    }

    @Getter
    @RequiredArgsConstructor
    public abstract class PermissionChecker<O> {
        private final String name;
        private final Permission<O> permission;

        public abstract Decision validate();

        public void assertThat() throws OrionSecurityException {
            permissionCheck(this);
        }

        public void assertThat(O o) throws OrionSecurityException {
            getSc().with(permission, o);
            permissionCheck(this);
        }
    }

    private static Decision allowIf(boolean condition) {
        return condition ? ALLOW : DENY;
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

    private static boolean hasAnyAllowedWantedBranch(List<AccessControl.Grant> branchGrants, Map<GitObjectId, String> wantedBranches) {
        return !filterGrants(branchGrants, GrantMatcher.of(AccessControl.GrantKey.BRANCH, wantedBranches::containsValue)).isEmpty();
    }
}
