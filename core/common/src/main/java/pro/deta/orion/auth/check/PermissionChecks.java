package pro.deta.orion.auth.check;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.NameRevCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.*;
import pro.deta.orion.auth.check.data.FetchRepositorySecurityCheck;
import pro.deta.orion.util.Pair;
import pro.deta.orion.util.Result;

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
        List<AccessControl.Grant> g = userIdentity.getGrants();
        // looking for all grants with CREATE and REPOSITORY
        List<AccessControl.Grant> matchedGrant = filterGrants(g, GrantMatcher.of(AccessControl.GrantKey.REPOSITORY, expressionValue -> matchExpressionValue(expressionValue, repositoryName)), GrantMatcher.of(AccessControl.GrantKey.CREATE));
        // looking match via name of REPOSITORY
        if (!matchedGrant.isEmpty()) {
            log.debug("Check ALLOW_TO_CREATE_REPO passed for {} on {}", userIdentity, repositoryName);
            return ALLOW;
        }
        return Decision.DENY;
    });

    public final PermissionChecker<FetchRepositorySecurityCheck> ALLOW_TO_FETCH_REPO = createFor(REPOSITORY_FETCH, "Allow fetch from repository", (userIdentity, frsc) -> {
        List<AccessControl.Grant> grantList = userIdentity.getGrants();

        // if branch specified for REPO
        List<AccessControl.Grant> matchedGrant = filterGrants(grantList,
                GrantMatcher.of(AccessControl.GrantKey.REPOSITORY, (expressionValue) -> matchExpressionValue(expressionValue, frsc.getRepositoryName())),
                GrantMatcher.of(AccessControl.GrantKey.BRANCH));

        if (!matchedGrant.isEmpty()) { // no branch restrictions -> ALLOW
            if (!filterGrants(matchedGrant, GrantMatcher.of(AccessControl.GrantKey.BRANCH, "*"::equalsIgnoreCase)).isEmpty())
                return ALLOW; // optimization, if BRANCH value = '*' no need to fetch it.

            // looking match via name of REPOSITORY
            NameRevCommand nameRev = frsc.getGit()
                    .nameRev()
                    .addPrefix("refs/heads");
            for (ObjectId w : frsc.getWants()) {
                try {
                    nameRev.add(w);
                } catch (MissingObjectException e) {
                    log.error("Can't find commit {} in repository {}", w, frsc.getRepository().getDirectory());
                    return DENY;
                }
            }
            Map<ObjectId, String> res;
            try {
                res = nameRev.call();
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
            List<AccessControl.Grant> matchedGrantForBranches = filterGrants(matchedGrant, GrantMatcher.of(AccessControl.GrantKey.BRANCH, res::containsValue));
            if (!matchedGrantForBranches.isEmpty())
                return ALLOW;
        } else
            return ALLOW;
        return Decision.DENY;
    });

    public final PermissionChecker<String> ALLOW_READ_ACCESS = createFor(REPOSITORY_READ, "Allow read access", (userIdentity, repository) -> {
        List<AccessControl.Grant> g = userIdentity.getGrants();
        List<AccessControl.Grant> matchedGrant = filterGrants(g, GrantMatcher.of(AccessControl.GrantKey.REPOSITORY, (value) -> matchExpressionValue(value, repository)));
        if (!matchedGrant.isEmpty()) {
            log.debug("Check ALLOW_TO_CREATE_REPO passed for {} on {}", userIdentity, matchedGrant);
            return ALLOW;
        }
        return DENY;
    });

    public final PermissionChecker<SocketAddress> ALLOW_ONLY_LOCAL_CONNECTIONS = createFor(Permission.CLIENT_SOCKET_ADDRESS, "Allow only local connections", (userIdentity, socketAddress) -> {
        if (socketAddress instanceof InetSocketAddress) {
            if (((InetSocketAddress) socketAddress).getAddress().isLoopbackAddress())
                return ALLOW;
        }
        return DENY;
    });

    public final PermissionChecker<UserIdentity> ALLOW_ANONYMOUS_ACCESS = createFor(Permission.USER_IDENTITY, "Allow anonymous access", (userIdentity, ui) -> {
        if (userIdentity != null && !userIdentity.isAnonymous())
            return ALLOW;
        return DENY;
    });


    public static PermissionChecks permissionChecker() {
        return _instance;
    }

    public <O> void permissionCheck(PermissionChecker<O> checker) throws OrionSecurityException {
        List<Pair<String, Decision>> results = new ArrayList<>();
        results.add(new Pair<>(checker.getName(), checker.validate()));

        if (log.isTraceEnabled()) {
            String info = results.stream().collect(StringBuilder::new, (sb, p) -> sb.append(p.getSecond().name()).append(" by ").append(p.getFirst()).append("\n"), StringBuilder::append).toString();
            log.atTrace().log("SC: {} results: {}", getSc(), info);
        }
        for (Pair<String, Decision> p : results) {
            if (p.getSecond() == Decision.DENY) {
                log.atWarn().log("ACCESS DENIED ({}): SC: {}", p.getFirst(), getSc());
                throw new OrionSecurityException("Origin: disallowed for [" + getSc().formatShort() + "] by '" + p.getFirst() + "'");
            }
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
}
