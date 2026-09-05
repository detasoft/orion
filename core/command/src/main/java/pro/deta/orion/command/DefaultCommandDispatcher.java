package pro.deta.orion.command;

import pro.deta.orion.auth.check.AccessDecision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultCommandDispatcher implements CommandDispatcher, CommandAuditDescriber {
    private static final String REDACTED = "<redacted>";

    private final CommandLineParser parser;
    private final CommandNode root;
    private final CommandNavigator navigator;
    private final CommandRowQuery rowQuery;

    public DefaultCommandDispatcher(
            CommandLineParser parser,
            CommandNode root,
            CommandRowQuery rowQuery) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.root = Objects.requireNonNull(root, "root");
        this.rowQuery = Objects.requireNonNull(rowQuery, "rowQuery");
        navigator = new CommandNavigator(root);
    }

    @Override
    public CommandResult dispatch(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.context().cancellation().isCancelled()) {
            return failure(CommandFailureCode.CANCELLED, "Command was cancelled");
        }
        CommandParseResult parseResult = parser.parse(request.commandLine(), request.context().currentPath());
        if (parseResult instanceof CommandParseResult.Failure parseFailure) {
            return failure(parseFailure.code(), parseFailure.message());
        }
        ParsedCommand command = ((CommandParseResult.Success) parseResult).command();
        try {
            return dispatchParsed(request.context(), command);
        } catch (Exception exception) {
            return failure(CommandFailureCode.HANDLER_FAILED, "Command handler failed");
        }
    }

    @Override
    public CommandAuditDescription describe(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        CommandParseResult parseResult = parser.parse(request.commandLine(), request.context().currentPath());
        if (!(parseResult instanceof CommandParseResult.Success success)) {
            return new CommandAuditDescription(request.context().currentPath().toString(), "", Map.of());
        }
        ParsedCommand command = success.command();
        CommandDefinition definition = findDefinitionShape(command);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 0; index < command.positionalArguments().size(); index++) {
            boolean classified = definition != null && index < definition.maximumPositionalArguments();
            parameters.put("$" + index, classified ? command.positionalArguments().get(index) : REDACTED);
        }
        for (Map.Entry<String, String> parameter : command.namedParameters().entrySet()) {
            boolean sensitive = definition == null
                    || !allowedNamedParameter(definition, parameter.getKey())
                    || definition.sensitiveNamedParameters().contains(parameter.getKey());
            parameters.put(parameter.getKey(), sensitive ? REDACTED : parameter.getValue());
        }
        for (WherePredicate predicate : command.predicates()) {
            boolean classified = definition != null
                    && definition.query().fields().contains(predicate.field());
            parameters.put("where." + predicate.field(), classified ? predicate.value() : REDACTED);
        }
        return new CommandAuditDescription(command.path().toString(), command.action(), parameters);
    }

    private CommandResult dispatchParsed(CommandContext context, ParsedCommand command) throws Exception {
        CommandNavigation navigation = navigator.locate(context, command.path());
        if (navigation instanceof CommandNavigation.UnknownPath) {
            return failure(CommandFailureCode.UNKNOWN_PATH, "Unknown command path");
        }
        if (navigation instanceof CommandNavigation.Missing) {
            return failure(CommandFailureCode.MISSING_RESOURCE, "Resource was not found");
        }
        if (navigation instanceof CommandNavigation.Ambiguous ambiguous) {
            return new CommandResult.Failure(
                    CommandFailureCode.AMBIGUOUS_RESOURCE,
                    "Resource selector is ambiguous",
                    ambiguous.candidates());
        }
        if (navigation instanceof CommandNavigation.Unavailable) {
            return failure(CommandFailureCode.SERVICE_UNAVAILABLE, "Resource service is unavailable");
        }
        if (navigation instanceof CommandNavigation.AccessDenied) {
            return failure(CommandFailureCode.ACCESS_DENIED, "Access denied");
        }
        if (navigation instanceof CommandNavigation.Failed) {
            return failure(CommandFailureCode.HANDLER_FAILED, "Resource lookup failed");
        }
        CommandLocation location = ((CommandNavigation.Located) navigation).location();
        CommandNode node = location.node();

        CommandDefinition definition = findAction(node, command.action());
        if (definition == null) {
            return failure(CommandFailureCode.UNKNOWN_COMMAND, "Unknown command");
        }
        CommandResult validationFailure = validateArguments(definition, command);
        if (validationFailure != null) {
            return validationFailure;
        }
        CommandInvocation invocation = new CommandInvocation(
                context,
                command.path(),
                command.action(),
                new CommandArguments(
                        command.positionalArguments(),
                        command.namedParameters(),
                        command.predicates()),
                location.resources());
        AccessDecision decision = Objects.requireNonNull(
                definition.authorization().authorize(invocation),
                "authorization result");
        if (!decision.allowed()) {
            return failure(CommandFailureCode.ACCESS_DENIED, "Access denied");
        }
        if (context.cancellation().isCancelled()) {
            return failure(CommandFailureCode.CANCELLED, "Command was cancelled");
        }
        CommandResult result = definition.handler().handle(invocation);
        if (result == null) {
            return failure(CommandFailureCode.HANDLER_FAILED, "Command handler failed");
        }
        if (!definition.query().enabled()
                || result instanceof CommandResult.Failure
                || !(result instanceof CommandResult.Rows)) {
            return result;
        }
        CommandResult.Rows rows = (CommandResult.Rows) result;
        return rowQuery.apply(rows, invocation.arguments(), definition.query(), context.presentation());
    }

    private static CommandResult validateArguments(CommandDefinition definition, ParsedCommand command) {
        int positionalCount = command.positionalArguments().size();
        if (positionalCount < definition.minimumPositionalArguments()
                || positionalCount > definition.maximumPositionalArguments()) {
            return failure(CommandFailureCode.INVALID_ARGUMENTS, "Invalid positional arguments");
        }
        for (String name : command.namedParameters().keySet()) {
            if (!allowedNamedParameter(definition, name)) {
                return failure(CommandFailureCode.INVALID_ARGUMENTS, "Unknown named parameter: " + name);
            }
        }
        for (WherePredicate predicate : command.predicates()) {
            if (!definition.query().enabled()) {
                return failure(
                        CommandFailureCode.INVALID_ARGUMENTS,
                        "Unknown where field: " + predicate.field());
            }
        }
        return null;
    }

    private static boolean allowedNamedParameter(CommandDefinition definition, String name) {
        return definition.allowedNamedParameters().contains(name)
                || definition.query().enabled() && CommandQuery.NAMED_PARAMETERS.contains(name);
    }

    private CommandDefinition findDefinitionShape(ParsedCommand command) {
        CommandNode node = root;
        for (String segment : command.path().segments()) {
            CommandNode child = node.children().get(segment);
            if (child != null) {
                node = child;
            } else if (node.dynamicChild() != null) {
                node = node.dynamicChild().node();
            } else {
                return null;
            }
        }
        return findAction(node, command.action());
    }

    private static CommandDefinition findAction(CommandNode node, String action) {
        CommandDefinition exact = node.actions().get(action);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, CommandDefinition> candidate : node.actions().entrySet()) {
            if (candidate.getKey().equalsIgnoreCase(action)) {
                return candidate.getValue();
            }
        }
        return null;
    }

    private static CommandResult.Failure failure(CommandFailureCode code, String message) {
        return new CommandResult.Failure(code, message, List.of());
    }
}
