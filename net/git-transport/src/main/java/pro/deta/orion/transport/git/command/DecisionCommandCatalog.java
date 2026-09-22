package pro.deta.orion.transport.git.command;

import jakarta.inject.Inject;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandHandler;
import pro.deta.orion.command.CommandInvocation;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandQuery;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.resource.ScopedResourceCandidate;
import pro.deta.orion.command.resource.ScopedResourceCatalogResult;
import pro.deta.orion.command.resource.ScopedResourceResolver;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.UserId;
import pro.deta.orion.util.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Exposes pending decisions through the existing command tree. The registry owns visibility and resolution;
 * resource lookup retains only an ID so each show or answer checks the live request again.
 * Current authentication supplies flat system user IDs, mapped to system principal addresses here.
 */
public final class DecisionCommandCatalog {
    private static final List<CommandColumn> COLUMNS = List.of(
            CommandColumn.text("id"), CommandColumn.text("scope"),
            CommandColumn.text("title"), CommandColumn.text("createdAt"));

    private final DecisionRegistry registry;

    @Inject
    public DecisionCommandCatalog(DecisionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public CommandNode commandTree() {
        CommandNode request = CommandNode.builder()
                .action(definition("show", 0, this::show))
                .action(definition("resolve", 1, this::resolve))
                .build();
        return CommandNode.builder()
                .child("decision", CommandNode.builder()
                        .action(definition("ls", 0, this::list))
                        .dynamicChild(new ScopedResourceResolver<>(this::candidates, false), request)
                        .build())
                .build();
    }

    private static CommandDefinition definition(String action, int arguments, CommandHandler handler) {
        return new CommandDefinition(action, arguments, arguments, Set.of(), Set.of(),
                context -> actor(context.securityContext()) != null,
                invocation -> actor(invocation.context().securityContext()) == null
                        ? AccessDecision.deny("Authenticated system user is required")
                        : AccessDecision.allow("Authenticated system user"),
                handler, CommandCompletion.none(), CommandQuery.none());
    }

    private ScopedResourceCatalogResult<UUID> candidates(CommandContext context, List<Object> parents) {
        PrincipalAddress actor = actor(context.securityContext());
        if (actor == null) {
            return new ScopedResourceCatalogResult.AccessDenied<>("Authenticated system user is required");
        }
        List<ScopedResourceCandidate<UUID>> candidates = new ArrayList<>();
        for (DecisionRequest request : registry.list(actor)) {
            candidates.add(new ScopedResourceCandidate<>(request.id().toString(), Optional.empty(), request.id(),
                    AccessDecision.allow("Visible decision request")));
        }
        return new ScopedResourceCatalogResult.Available<>(candidates);
    }

    private CommandResult list(CommandInvocation invocation) {
        List<List<CommandValue>> rows = new ArrayList<>();
        for (DecisionRequest request : registry.list(actor(invocation.context().securityContext()))) {
            rows.add(List.of(CommandValue.text(request.id().toString()), CommandValue.text(scope(request)),
                    CommandValue.text(request.title()), CommandValue.text(request.createdAt().toString())));
        }
        return CommandResult.Rows.unqueried(COLUMNS, rows);
    }

    private CommandResult show(CommandInvocation invocation) {
        Optional<DecisionRequest> found = registry.find(requestId(invocation),
                actor(invocation.context().securityContext()));
        if (found.isEmpty()) {
            return unavailable();
        }
        DecisionRequest request = found.orElseThrow();
        Map<String, CommandValue> fields = new LinkedHashMap<>();
        fields.put("id", CommandValue.text(request.id().toString()));
        fields.put("scope", CommandValue.text(scope(request)));
        fields.put("title", CommandValue.text(request.title()));
        fields.put("createdAt", CommandValue.text(request.createdAt().toString()));
        fields.put("description", CommandValue.text(request.description()));
        for (Map.Entry<String, String> action : request.actions().entrySet()) {
            fields.put("action." + action.getKey(), CommandValue.text(action.getValue()));
        }
        return new CommandResult.ObjectValue(fields);
    }

    private CommandResult resolve(CommandInvocation invocation) {
        String action = invocation.arguments().positional().getFirst();
        if (action.isBlank()) {
            return new CommandResult.Failure(CommandFailureCode.INVALID_ARGUMENTS,
                    "Decision action is required", List.of());
        }
        Result<Decision> result = registry.decide(requestId(invocation),
                new Decision(action, actor(invocation.context().securityContext())));
        return switch (result) {
            case Result.Success<Decision> ignored -> new CommandResult.Message("Decision recorded");
            case Result.Failure<Decision> failure -> switch (failure.code()) {
                case NOT_FOUND -> unavailable();
                case NOT_SUPPORTED -> new CommandResult.Failure(CommandFailureCode.INVALID_ARGUMENTS,
                        "Decision action is unavailable", List.of());
                default -> new CommandResult.Failure(CommandFailureCode.HANDLER_FAILED,
                        "Could not record decision", List.of());
            };
        };
    }

    private static UUID requestId(CommandInvocation invocation) {
        return (UUID) invocation.resolvedResources().getLast();
    }

    private static String scope(DecisionRequest request) {
        return request.scope().map(ConfigurationScope::toString).orElse("system");
    }

    private static PrincipalAddress actor(SecurityContext context) {
        UserIdentity identity = context.getUserIdentity();
        if (identity == null || identity.isAnonymous() || identity.getUserId() == null) {
            return null;
        }
        try {
            return new PrincipalAddress.SystemPrincipalAddress(new UserId(identity.getUserId()));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static CommandResult.Failure unavailable() {
        return new CommandResult.Failure(CommandFailureCode.MISSING_RESOURCE,
                "Decision request is unavailable", List.of());
    }
}
