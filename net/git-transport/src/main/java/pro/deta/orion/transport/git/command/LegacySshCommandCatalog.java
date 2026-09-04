package pro.deta.orion.transport.git.command;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.ApplicationShutdownResource;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandInvocation;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.util.OrionProvider;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class LegacySshCommandCatalog {
    private static final Set<String> NO_PARAMETERS = Set.of();

    private final OrionAccessControlService accessControlService;
    private final AggregateStateMachine runtimeStateMachine;
    private final NativeGitRepositoryProvider repositoryProvider;
    private final Runnable shutdownAction;
    private final SshCredentialCommandCatalog sshCredentialCommandCatalog;

    @Inject
    public LegacySshCommandCatalog(
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            @Named("runtime") AggregateStateMachine runtimeStateMachine,
            NativeGitRepositoryProvider repositoryProvider,
            SshCredentialCommandCatalog sshCredentialCommandCatalog) {
        this(
                accessControlService,
                runtimeStateMachine,
                repositoryProvider,
                () -> orionProvider.getOrionApplicationLifecycle().beginShutdown(),
                sshCredentialCommandCatalog);
    }

    LegacySshCommandCatalog(
            OrionAccessControlService accessControlService,
            AggregateStateMachine runtimeStateMachine,
            NativeGitRepositoryProvider repositoryProvider,
            Runnable shutdownAction) {
        this(
                accessControlService,
                runtimeStateMachine,
                repositoryProvider,
                shutdownAction,
                new SshCredentialCommandCatalog(accessControlService));
    }

    private LegacySshCommandCatalog(
            OrionAccessControlService accessControlService,
            AggregateStateMachine runtimeStateMachine,
            NativeGitRepositoryProvider repositoryProvider,
            Runnable shutdownAction,
            SshCredentialCommandCatalog sshCredentialCommandCatalog) {
        this.accessControlService = Objects.requireNonNull(accessControlService, "accessControlService");
        this.runtimeStateMachine = Objects.requireNonNull(runtimeStateMachine, "runtimeStateMachine");
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
        this.sshCredentialCommandCatalog = Objects.requireNonNull(
                sshCredentialCommandCatalog,
                "sshCredentialCommandCatalog");
    }

    public CommandNode commandTree() {
        return CommandNode.builder()
                .child("auth", sshCredentialCommandCatalog.commandTree().children().get("auth"))
                .action(tokenDefinition("issue-token"))
                .action(tokenDefinition("token"))
                .action(adminDefinition("state", this::lifecycleStatus))
                .action(adminDefinition("status", this::lifecycleStatus))
                .action(adminDefinition("repositories", this::repositories))
                .action(shutdownDefinition())
                .build();
    }

    private CommandDefinition tokenDefinition(String action) {
        return definition(action, 1, this::authenticated, this::issueToken);
    }

    private CommandDefinition adminDefinition(String action, Handler handler) {
        return definition(action, 0, this::admin, handler);
    }

    private CommandDefinition shutdownDefinition() {
        return definition("shutdown", 0, this::shutdown, invocation -> {
            shutdownAction.run();
            return new CommandResult.Message("");
        });
    }

    private static CommandDefinition definition(
            String action,
            int positionalArguments,
            pro.deta.orion.command.CommandAuthorization authorization,
            Handler handler) {
        return new CommandDefinition(
                action,
                positionalArguments,
                positionalArguments,
                NO_PARAMETERS,
                NO_PARAMETERS,
                NO_PARAMETERS,
                context -> true,
                authorization,
                handler::handle);
    }

    private AccessDecision authenticated(CommandInvocation invocation) {
        return SubjectAccessRules.authenticated().evaluate(invocation.context().securityContext());
    }

    private AccessDecision admin(CommandInvocation invocation) {
        AccessDecision authenticated = authenticated(invocation);
        if (!authenticated.allowed()) {
            return authenticated;
        }
        return ApplicationAccessRules.admin().evaluate(
                invocation.context().securityContext(),
                ApplicationAdminResource.applicationAdmin());
    }

    private AccessDecision shutdown(CommandInvocation invocation) {
        AccessDecision authenticated = authenticated(invocation);
        if (!authenticated.allowed()) {
            return authenticated;
        }
        return ApplicationAccessRules.shutdown().evaluate(
                invocation.context().securityContext(),
                ApplicationShutdownResource.applicationShutdown());
    }

    private CommandResult issueToken(CommandInvocation invocation) {
        String value = invocation.arguments().positional().getFirst();
        long expiresInSeconds;
        try {
            expiresInSeconds = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return failure(
                    CommandFailureCode.INVALID_ARGUMENTS,
                    "Token expiration must be a number of seconds");
        }
        if (expiresInSeconds <= 0) {
            return failure(CommandFailureCode.INVALID_ARGUMENTS, "Token expiration must be positive");
        }
        TokenIssueResult result = accessControlService.issueTokenFor(
                invocation.context().securityContext().getUserIdentity(),
                expiresInSeconds);
        return switch (result) {
            case TokenIssueResult.Success success -> new CommandResult.Message(success.token());
            case TokenIssueResult.Failure ignored ->
                    failure(CommandFailureCode.HANDLER_FAILED, "Token issuance failed");
        };
    }

    private CommandResult lifecycleStatus(CommandInvocation invocation) {
        return new CommandResult.Message(runtimeStateMachine.describeStatus());
    }

    private CommandResult repositories(CommandInvocation invocation) {
        return new CommandResult.Message(String.join("\n", repositoryProvider.repositoryNames()));
    }

    private static CommandResult.Failure failure(CommandFailureCode code, String message) {
        return new CommandResult.Failure(code, message, List.of());
    }

    @FunctionalInterface
    private interface Handler {
        CommandResult handle(CommandInvocation invocation) throws Exception;
    }
}
