package pro.deta.orion.command;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public record CommandDefinition(
        String action,
        int minimumPositionalArguments,
        int maximumPositionalArguments,
        Set<String> allowedNamedParameters,
        Set<String> sensitiveNamedParameters,
        Predicate<CommandContext> visibility,
        CommandAuthorization authorization,
        CommandHandler handler,
        CommandCompletion completion,
        CommandQuery query) {
    public CommandDefinition {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(allowedNamedParameters, "allowedNamedParameters");
        Objects.requireNonNull(sensitiveNamedParameters, "sensitiveNamedParameters");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(query, "query");
        if (action.isEmpty()) {
            throw new IllegalArgumentException("action must not be empty");
        }
        if (minimumPositionalArguments < 0 || maximumPositionalArguments < minimumPositionalArguments) {
            throw new IllegalArgumentException("invalid positional argument range");
        }
        allowedNamedParameters = Set.copyOf(allowedNamedParameters);
        sensitiveNamedParameters = Set.copyOf(sensitiveNamedParameters);
        if (!allowedNamedParameters.containsAll(sensitiveNamedParameters)) {
            throw new IllegalArgumentException("sensitive parameters must be allowed parameters");
        }
        if (query.enabled() && !java.util.Collections.disjoint(
                allowedNamedParameters, CommandQuery.NAMED_PARAMETERS)) {
            throw new IllegalArgumentException("query parameter names are reserved");
        }
    }
}
