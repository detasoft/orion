package pro.deta.orion.transport.git.command;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.registry.AgentRegistryException;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.provisioning.ProvisioningException;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.ApplicationShutdownResource;
import pro.deta.orion.command.decision.DecisionCommandCatalog;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandCompletion;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandInvocation;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandQuery;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.util.OrionProvider;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class LegacySshCommandCatalog {
    private static final Set<String> NO_PARAMETERS = Set.of();

    private final OrionAccessControlService accessControlService;
    private final AggregateStateMachine runtimeStateMachine;
    private final NativeGitRepositoryProvider repositoryProvider;
    private final Runnable shutdownAction;
    private final SshCredentialCommandCatalog sshCredentialCommandCatalog;
    private final ReadOnlyDomainCommandCatalog readOnlyDomainCommandCatalog;
    private final DecisionCommandCatalog decisionCommandCatalog;
    private final Provider<AgentSessionServer> agentServer;

    @Inject
    public LegacySshCommandCatalog(
            OrionProvider orionProvider,
            OrionAccessControlService accessControlService,
            @Named("runtime") AggregateStateMachine runtimeStateMachine,
            NativeGitRepositoryProvider repositoryProvider,
            SshCredentialCommandCatalog sshCredentialCommandCatalog,
            ReadOnlyDomainCommandCatalog readOnlyDomainCommandCatalog,
            DecisionCommandCatalog decisionCommandCatalog,
            Provider<AgentSessionServer> agentServer) {
        this(
                accessControlService,
                runtimeStateMachine,
                repositoryProvider,
                () -> orionProvider.getOrionApplicationLifecycle().beginShutdown(),
                sshCredentialCommandCatalog,
                readOnlyDomainCommandCatalog,
                decisionCommandCatalog,
                agentServer);
    }

    LegacySshCommandCatalog(
            OrionAccessControlService accessControlService,
            AggregateStateMachine runtimeStateMachine,
            NativeGitRepositoryProvider repositoryProvider,
            Runnable shutdownAction,
            ReadOnlyDomainCommandCatalog readOnlyDomainCommandCatalog,
            DecisionCommandCatalog decisionCommandCatalog,
            Provider<AgentSessionServer> agentServer) {
        this(
                accessControlService,
                runtimeStateMachine,
                repositoryProvider,
                shutdownAction,
                new SshCredentialCommandCatalog(accessControlService),
                readOnlyDomainCommandCatalog,
                decisionCommandCatalog,
                agentServer);
    }

    private LegacySshCommandCatalog(
            OrionAccessControlService accessControlService,
            AggregateStateMachine runtimeStateMachine,
            NativeGitRepositoryProvider repositoryProvider,
            Runnable shutdownAction,
            SshCredentialCommandCatalog sshCredentialCommandCatalog,
            ReadOnlyDomainCommandCatalog readOnlyDomainCommandCatalog,
            DecisionCommandCatalog decisionCommandCatalog,
            Provider<AgentSessionServer> agentServer) {
        this.accessControlService = Objects.requireNonNull(accessControlService, "accessControlService");
        this.runtimeStateMachine = Objects.requireNonNull(runtimeStateMachine, "runtimeStateMachine");
        this.repositoryProvider = Objects.requireNonNull(repositoryProvider, "repositoryProvider");
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
        this.sshCredentialCommandCatalog = Objects.requireNonNull(
                sshCredentialCommandCatalog,
                "sshCredentialCommandCatalog");
        this.readOnlyDomainCommandCatalog = Objects.requireNonNull(
                readOnlyDomainCommandCatalog,
                "readOnlyDomainCommandCatalog");
        this.decisionCommandCatalog = Objects.requireNonNull(decisionCommandCatalog, "decisionCommandCatalog");
        this.agentServer = Objects.requireNonNull(agentServer, "agentServer");
    }

    public CommandNode commandTree() {
        CommandNode readOnly = readOnlyDomainCommandCatalog.commandTree();
        CommandNode.Builder builder = CommandNode.builder()
                .child("auth", sshCredentialCommandCatalog.commandTree().children().get("auth"))
                .child("decision", decisionCommandCatalog.commandTree().children().get("decision"));
        for (var child : readOnly.children().entrySet()) {
            builder.child(child.getKey(), child.getValue());
        }
        for (CommandDefinition action : readOnly.actions().values()) {
            builder.action(action);
        }
        return builder
                .action(tokenDefinition("issue-token"))
                .action(tokenDefinition("token"))
                .action(new CommandDefinition("issue-launch-permit", 4, 4, Set.of("allow-unsecure"),
                        NO_PARAMETERS, context -> true, this::admin, this::issueLaunchPermit,
                        CommandCompletion.none(), CommandQuery.none()))
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
                context -> true,
                authorization,
                handler::handle,
                CommandCompletion.none(),
                CommandQuery.none());
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
        TokenRefreshResult result = accessControlService.refreshToken(
                new AuthenticationResult.Success(
                        invocation.context().securityContext().getUserIdentity()),
                expiresInSeconds);
        return switch (result) {
            case TokenRefreshResult.Success success -> new CommandResult.Message(success.token());
            case TokenRefreshResult.Failure ignored ->
                    failure(CommandFailureCode.HANDLER_FAILED, "Token issuance failed");
        };
    }

    private CommandResult lifecycleStatus(CommandInvocation invocation) {
        return new CommandResult.Message(runtimeStateMachine.describeStatus());
    }

    private CommandResult issueLaunchPermit(CommandInvocation invocation) {
        List<String> arguments = invocation.arguments().positional();
        try {
            AgentSessionServer server = agentServer.get();
            AgentLabel label = new AgentLabel(arguments.get(0));
            var control = server.provisioningControl(label, URI.create(arguments.get(1)), arguments.get(2),
                    AgentProtocolLimits.DEFAULT_MAX_FRAME_BYTES, arguments.get(3),
                    "true".equals(invocation.arguments().named().get("allow-unsecure")));
            server.registerAgent(label, label.value());
            try (var attempt = control.nextAttempt()) {
                byte[] permit = attempt.permit().copyBytes();
                try {
                    return new CommandResult.Message(attempt.request().generation().value() + "\n"
                            + attempt.request().launchId().value() + "\n"
                            + new String(permit, StandardCharsets.US_ASCII));
                } finally {
                    Arrays.fill(permit, (byte) 0);
                }
            }
        } catch (IllegalArgumentException failure) {
            return failure(CommandFailureCode.INVALID_ARGUMENTS, "Invalid AgentD launch parameters");
        } catch (ProvisioningException | AgentRegistryException | IllegalStateException failure) {
            return failure(CommandFailureCode.HANDLER_FAILED, "Could not issue an AgentD launch permit");
        }
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
