package pro.deta.orion.auth.check.rule;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.AccessRule;
import pro.deta.orion.auth.check.ProtectedResource;

import java.util.Objects;
import java.util.function.BiFunction;

record NamedAccessRule<R extends ProtectedResource>(
        String name,
        BiFunction<SecurityContext, R, AccessDecision> evaluator) implements AccessRule<R> {

    NamedAccessRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public AccessDecision evaluate(SecurityContext securityContext, R resource) {
        return evaluator.apply(securityContext, resource);
    }
}
