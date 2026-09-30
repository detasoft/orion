package pro.deta.orion.transport.git.command;

import jakarta.inject.Inject;
import pro.deta.orion.auth.StorageManagement;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.command.*;
import pro.deta.orion.schema.orion.ConnectionReference;
import pro.deta.orion.schema.orion.S3StorageBinding;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** SSH adapts existing connection references to the same creation operation used by HTTP. */
public final class RepositoryCreationCommand {
    private final StorageManagement management;

    @Inject
    public RepositoryCreationCommand(StorageManagement management) { this.management = management; }

    public CommandDefinition definition() {
        return new CommandDefinition("create", 1, 1, Set.of("connection", "location"), Set.of(),
                context -> true, invocation -> invocation.context().securityContext().getUserIdentity().isAnonymous()
                        ? AccessDecision.deny("Authentication required") : AccessDecision.allow("Authenticated"),
                this::create, CommandCompletion.none(), CommandQuery.none());
    }

    private CommandResult create(CommandInvocation invocation) {
        Optional<S3StorageBinding> storage = Optional.empty();
        Map<String, String> named = invocation.arguments().named();
        if (!named.isEmpty()) {
            try {
                String[] reference = named.get("connection").split("/", -1);
                if (reference.length != 2) throw new IllegalArgumentException();
                storage = Optional.of(new S3StorageBinding(new ConnectionReference(
                        ConnectionReference.Scope.valueOf(reference[0].toUpperCase(java.util.Locale.ROOT)),
                        reference[1]), URI.create(named.get("location"))));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                return new CommandResult.Failure(CommandFailureCode.INVALID_ARGUMENTS,
                        "Use connection=organization/name or system/name with location=s3://bucket/prefix", List.of());
            }
        }
        StorageManagement.Outcome<StorageManagement.Created> result = management.createRepository(
                invocation.context().securityContext(), invocation.arguments().positional().getFirst(), storage);
        if (result instanceof StorageManagement.Success<StorageManagement.Created> success) {
            return new CommandResult.Message(success.value().created() ? "Repository created" : "Repository already exists");
        }
        StorageManagement.Failure<?> failure = (StorageManagement.Failure<?>) result;
        CommandFailureCode code = switch (failure.code()) {
            case DENIED -> CommandFailureCode.ACCESS_DENIED;
            case INVALID, CONFLICT -> CommandFailureCode.INVALID_ARGUMENTS;
            case STORAGE_RETRY, UNAVAILABLE -> CommandFailureCode.SERVICE_UNAVAILABLE;
        };
        return new CommandResult.Failure(code, failure.message(), List.of());
    }
}
