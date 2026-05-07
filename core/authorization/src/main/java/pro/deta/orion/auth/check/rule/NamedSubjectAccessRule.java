package pro.deta.orion.auth.check.rule;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.SubjectAccessRule;

import java.util.Objects;
import java.util.function.Function;

/**
 * Small adapter used by rule factories that expose named subject-only rules without creating a class per rule.
 */
record NamedSubjectAccessRule(
        String name,
        Function<SecurityContext, AccessDecision> evaluator) implements SubjectAccessRule {

    NamedSubjectAccessRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public AccessDecision evaluate(SecurityContext securityContext) {
        return evaluator.apply(securityContext);
    }
}
