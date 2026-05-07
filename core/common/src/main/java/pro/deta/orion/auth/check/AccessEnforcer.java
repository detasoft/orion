package pro.deta.orion.auth.check;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.auth.SecurityContext;

/**
 * Boundary between authorization decisions and application control flow. Subject and resource rules return
 * decisions; the enforcer is the only layer that converts a denied decision into {@link OrionSecurityException}.
 */
@Slf4j
public class AccessEnforcer {
    private static final AccessEnforcer INSTANCE = new AccessEnforcer();

    public static AccessEnforcer accessEnforcer() {
        return INSTANCE;
    }

    public <R extends ProtectedResource> void require(SecurityContext securityContext, R resource, AccessRule<R> rule) throws OrionSecurityException {
        AccessDecision decision = rule.evaluate(securityContext, resource);
        if (log.isTraceEnabled()) {
            log.atTrace().log("SC: {} resource: {} rule: {} decision: {}",
                    securityContext,
                    resource.describe(),
                    rule.name(),
                    decision);
        }
        if (!decision.allowed()) {
            log.atWarn().log("ACCESS DENIED ({}): {} for SC: {}", rule.name(), decision.reason(), securityContext);
            throw new OrionSecurityException("Origin: disallowed for ["
                    + securityContext.formatShort()
                    + "] by '"
                    + rule.name()
                    + "' on "
                    + resource.describe()
                    + ": "
                    + decision.reason());
        }
    }

    public void require(SecurityContext securityContext, SubjectAccessRule rule) throws OrionSecurityException {
        AccessDecision decision = rule.evaluate(securityContext);
        if (log.isTraceEnabled()) {
            log.atTrace().log("SC: {} subject rule: {} decision: {}", securityContext, rule.name(), decision);
        }
        if (!decision.allowed()) {
            log.atWarn().log("ACCESS DENIED ({}): {} for SC: {}", rule.name(), decision.reason(), securityContext);
            throw new OrionSecurityException("Origin: disallowed for ["
                    + securityContext.formatShort()
                    + "] by '"
                    + rule.name()
                    + "': "
                    + decision.reason());
        }
    }
}
