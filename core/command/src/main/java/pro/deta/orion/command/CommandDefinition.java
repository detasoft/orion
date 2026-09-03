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
        Set<String> allowedWhereFields,
        Predicate<CommandContext> visibility,
        CommandAuthorization authorization,
        CommandHandler handler,
        CommandCompletion completion) {
    public CommandDefinition {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(allowedNamedParameters, "allowedNamedParameters");
        Objects.requireNonNull(sensitiveNamedParameters, "sensitiveNamedParameters");
        Objects.requireNonNull(allowedWhereFields, "allowedWhereFields");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(completion, "completion");
        if (action.isEmpty()) {
            throw new IllegalArgumentException("action must not be empty");
        }
        if (minimumPositionalArguments < 0 || maximumPositionalArguments < minimumPositionalArguments) {
            throw new IllegalArgumentException("invalid positional argument range");
        }
        allowedNamedParameters = Set.copyOf(allowedNamedParameters);
        sensitiveNamedParameters = Set.copyOf(sensitiveNamedParameters);
        allowedWhereFields = Set.copyOf(allowedWhereFields);
        if (!allowedNamedParameters.containsAll(sensitiveNamedParameters)) {
            throw new IllegalArgumentException("sensitive parameters must be allowed parameters");
        }
    }

    public CommandDefinition(
            String action,
            int minimumPositionalArguments,
            int maximumPositionalArguments,
            Set<String> allowedNamedParameters,
            Set<String> sensitiveNamedParameters,
            Set<String> allowedWhereFields,
            Predicate<CommandContext> visibility,
            CommandAuthorization authorization,
            CommandHandler handler) {
        this(
                action,
                minimumPositionalArguments,
                maximumPositionalArguments,
                allowedNamedParameters,
                sensitiveNamedParameters,
                allowedWhereFields,
                visibility,
                authorization,
                handler,
                CommandCompletion.none());
    }
}
