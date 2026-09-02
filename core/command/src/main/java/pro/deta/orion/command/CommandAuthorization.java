package pro.deta.orion.command;

import pro.deta.orion.auth.check.AccessDecision;

@FunctionalInterface
public interface CommandAuthorization {
    AccessDecision authorize(CommandInvocation invocation);
}
