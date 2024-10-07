package pro.deta.orion.auth.check;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.auth.*;
import pro.deta.orion.util.Pair;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.auth.Decision.ALLOW;
import static pro.deta.orion.auth.Decision.DENY;
import static pro.deta.orion.auth.Permission.REPOSITORY_CREATE;
import static pro.deta.orion.auth.SecurityContextHolder.getSc;

@Slf4j
public class PermissionChecks {
    private static final PermissionChecks _instance = new PermissionChecks();

    public final PermissionChecker<String> ALLOW_TO_CREATE_REPO = createFor(REPOSITORY_CREATE, "Allow create new repositories", (String repositoryName) -> {
        if(getSc().getAttribute(Permission.USER_IDENTITY).isAnonymous())
            return Decision.DENY;
        return Decision.ALLOW;
    });

    public final PermissionChecker<SocketAddress> ALLOW_ONLY_LOCAL_CONNECTIONS = createFor(Permission.CLIENT_SOCKET_ADDRESS, "Allow only local connections", (SocketAddress address) -> {
        if (address instanceof InetSocketAddress) {
            if (((InetSocketAddress) address).getAddress().isLoopbackAddress())
                return ALLOW;
        }
        return DENY;
    });

    public final PermissionChecker<UserIdentity> ALLOW_ANONYMOUS_ACCESS = createFor(Permission.USER_IDENTITY, "Allow anonymous access", (UserIdentity userIdentity) -> {
        if (userIdentity.isAnonymous())
            return DENY;
        return ALLOW;
    });


    public static PermissionChecks permissionChecker() {
        return _instance;
    }

    public <O> void permissionCheck(PermissionChecker<O> checker) throws SecurityException {
        List<Pair<String, Decision>> results = new ArrayList<>();
        results.add(new Pair<>(checker.getName(), checker.validate()));

        if (log.isTraceEnabled()) {
            String info = results.stream().collect(StringBuilder::new, (sb, p) -> sb.append(p.getSecond().name()).append(" by ").append(p.getFirst()).append("\n"), StringBuilder::append).toString();
            log.atTrace().log("SC: {} results: {}", getSc(), info);
        }
        for (Pair<String, Decision> p : results) {
            if (p.getSecond() == Decision.DENY) {
                log.atWarn().log("ACCESS DENIED ({}): SC: {}", p.getFirst(), getSc());
                throw new SecurityException("Origin: disallowed for [" + getSc().formatShort() + "] by '" + p.getFirst() + "'");
            }
        }
    }

    public <O> PermissionChecker<O> createFor(Permission<O> permission, String name, Validator<O> func) {
        return new PermissionChecker<>(name, permission) {
            @Override
            public Decision validate() {
                return func.validate(getSc().getAttribute(permission));
            }
        };
    }

    @FunctionalInterface
    public interface Validator<O> {
        Decision validate(O object);

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

        public void assertThat() {
            permissionCheck(this);
        }

        public void assertThat(O o) {
            getSc().with(permission, o);
            permissionCheck(this);
        }
    }
}
