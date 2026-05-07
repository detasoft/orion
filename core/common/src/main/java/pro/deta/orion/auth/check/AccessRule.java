package pro.deta.orion.auth.check;

import pro.deta.orion.auth.SecurityContext;

/**
 * Evaluates whether the request subject from {@link SecurityContext} may access one specific resource type.
 * Rules contain authorization logic only; they do not throw security exceptions or write protocol responses.
 */
public interface AccessRule<R extends ProtectedResource> {
    String name();

    AccessDecision evaluate(SecurityContext securityContext, R resource);
}
