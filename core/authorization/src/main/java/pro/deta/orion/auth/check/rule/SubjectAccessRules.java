package pro.deta.orion.auth.check.rule;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.SubjectAccessRule;

/**
 * Rules for the request subject itself. These checks use only {@link SecurityContext}; modelling them as
 * protected resources would blur the line between who performs the request and what the request accesses.
 */
public final class SubjectAccessRules {
    private static final SubjectAccessRule AUTHENTICATED =
            new NamedSubjectAccessRule("authenticated user", SubjectAccessRules::evaluateAuthenticated);

    private SubjectAccessRules() {
    }

    public static SubjectAccessRule authenticated() {
        return AUTHENTICATED;
    }

    private static AccessDecision evaluateAuthenticated(SecurityContext securityContext) {
        UserIdentity userIdentity = securityContext.getUserIdentity();
        if (userIdentity != null && !userIdentity.isAnonymous()) {
            return AccessDecision.allow("user is authenticated");
        }
        return AccessDecision.deny("user is anonymous");
    }
}
