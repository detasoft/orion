package pro.deta.orion.auth.check;

import pro.deta.orion.auth.SecurityContext;

/**
 * Evaluates access requirements that apply to the request subject itself. These rules do not take
 * a {@link ProtectedResource} because there is no target object; the protected condition is in the context.
 */
public interface SubjectAccessRule {
    String name();

    AccessDecision evaluate(SecurityContext securityContext);
}
