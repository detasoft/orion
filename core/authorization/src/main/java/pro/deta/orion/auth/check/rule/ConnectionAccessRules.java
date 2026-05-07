package pro.deta.orion.auth.check.rule;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.AccessRule;
import pro.deta.orion.auth.check.resource.ClientConnectionResource;

import java.net.InetSocketAddress;

/**
 * Rules for transport connection metadata, such as restricting native git protocol clients to loopback addresses.
 */
public final class ConnectionAccessRules {
    private static final AccessRule<ClientConnectionResource> LOCAL_ONLY =
            new NamedAccessRule<>("local connection", ConnectionAccessRules::evaluateLocalOnly);

    private ConnectionAccessRules() {
    }

    public static AccessRule<ClientConnectionResource> localOnly() {
        return LOCAL_ONLY;
    }

    private static AccessDecision evaluateLocalOnly(SecurityContext securityContext, ClientConnectionResource resource) {
        if (resource.remoteAddress() instanceof InetSocketAddress inetSocketAddress
                && inetSocketAddress.getAddress() != null
                && inetSocketAddress.getAddress().isLoopbackAddress()) {
            return AccessDecision.allow("remote address is loopback");
        }
        return AccessDecision.deny("remote address is not loopback");
    }
}
