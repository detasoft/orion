package pro.deta.orion.transport.git.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.*;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.auth.AuthenticatedConnectionContext;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.RowOutputFormat;
import pro.deta.orion.command.RowPage;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.transport.git.command.read.OperatorDomainSource;
import pro.deta.orion.transport.git.command.read.OperatorDomainViews;
import pro.deta.orion.transport.git.command.read.OperatorQueryResult;
import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.schema.acl.AccessControl.TRUE_STRING;

class LegacySshCommandCatalogTest {
    @TempDir
    Path root;
    private AgentSessionServer agentServer;
    private final RecordingAccessControlService accessControl = new RecordingAccessControlService();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final CommandNode commandTree = new LegacySshCommandCatalog(
            accessControl,
            new AggregateStateMachine(StateMachineDefinition.define().name("runtime").build()),
            new RepositoryProvider(),
            () -> shutdown.set(true),
            new ReadOnlyDomainCommandCatalog(new DomainSource()), () -> agentServer)
            .commandTree();
    private final CommandDispatcher dispatcher = new DefaultCommandDispatcher(
            new CommandLineParser(),
            commandTree,
            new pro.deta.orion.command.CommandRowQuery());

    @Test
    void tokenAliasesIssueTokensWithPositiveExpiry() {
        assertThat(dispatch("IsSuE-ToKeN 600", user(List.of())))
                .isEqualTo(new CommandResult.Message("issued-secret"));
        assertThat(dispatch("ToKeN 300", user(List.of())))
                .isEqualTo(new CommandResult.Message("issued-secret"));
        assertThat(accessControl.expiries).containsExactly(600L, 300L);
    }

    @Test
    void tokenAliasesRejectInvalidZeroAndExtraExpiryArguments() {
        assertFailure(dispatch("issue-token invalid", user(List.of())), CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(dispatch("issue-token 0", user(List.of())), CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(dispatch("issue-token 1 extra", user(List.of())), CommandFailureCode.INVALID_ARGUMENTS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void launchPermitRequiresAdminAndValidLaunchParameters(boolean allowUnsecure) throws Exception {
        String command = "issue-launch-permit local "
                + (allowUnsecure ? "http://localhost:8000" : "https://localhost:8443")
                + " '/tmp/local agent' dev" + (allowUnsecure ? " --allow-unsecure" : "");
        assertFailure(dispatch(command, SecurityContext.ANONYMOUS), CommandFailureCode.ACCESS_DENIED);
        assertFailure(dispatch(command, user(List.of())), CommandFailureCode.ACCESS_DENIED);
        agentServer = new AgentSessionServer(root);
        agentServer.onStart();
        try {
            UserIdentity admin = user(List.of(grant(AccessControl.GrantKey.ADMIN)));
            assertFailure(dispatch("issue-launch-permit local http://localhost /tmp/agent dev", admin),
                    CommandFailureCode.INVALID_ARGUMENTS);
            assertFailure(dispatch("issue-launch-permit local", admin), CommandFailureCode.INVALID_ARGUMENTS);
            CommandResult result = dispatch(command, admin);
            assertThat(result).isInstanceOf(CommandResult.Message.class);
            String first = ((CommandResult.Message) result).value();
            assertThat(first.split("\\n")).hasSize(3);
            assertThat(first.split("\\n")[0]).isEqualTo("1");
            assertThat(java.util.Base64.getUrlDecoder().decode(first.split("\\n")[2])).hasSize(32);
            PermitConnection original = new PermitConnection();
            var firstHello = hello(first);
            agentServer.open(original).onMessage(firstHello);
            assertThat(original.authenticated).isTrue();
            PermitConnection replay = new PermitConnection();
            agentServer.open(replay).onMessage(firstHello);
            assertThat(replay.authenticated).isFalse();
            assertThat(replay.closed).isTrue();
            String next = ((CommandResult.Message) dispatch(command, admin)).value();
            assertThat(original.closed).isFalse();
            PermitConnection replacement = new PermitConnection();
            agentServer.open(replacement).onMessage(hello(next));
            assertThat(replacement.authenticated).isTrue();
            assertThat(original.closed).isTrue();
            assertThat(next.split("\\n")[0]).isEqualTo("2");
            assertThat(next.split("\\n")[1]).isNotEqualTo(first.split("\\n")[1]);
            assertThat(next.split("\\n")[2]).isNotEqualTo(first.split("\\n")[2]);
        } finally {
            agentServer.onStop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"show", "ls", "rm"})
    void launchPermitAcceptsActionWordsAsAgentLabels(String label) throws Exception {
        String command = "issue-launch-permit " + label + " https://localhost:8443 /tmp/agent dev";
        assertFailure(dispatch(command, user(List.of())), CommandFailureCode.ACCESS_DENIED);
        agentServer = new AgentSessionServer(root);
        agentServer.onStart();
        try {
            CommandResult result = dispatch(command, user(List.of(grant(AccessControl.GrantKey.ADMIN))));
            assertThat(result).isInstanceOf(CommandResult.Message.class);
            String[] permit = ((CommandResult.Message) result).value().split("\\n");
            assertThat(permit).hasSize(3);
            assertThat(permit[0]).isEqualTo("1");
            assertThat(java.util.Base64.getUrlDecoder().decode(permit[2])).hasSize(32);
        } finally {
            agentServer.onStop();
        }
    }

    @Test
    void stateAliasesAndRepositoriesPreserveExistingOutput() {
        UserIdentity admin = user(List.of(grant(AccessControl.GrantKey.ADMIN)));

        assertThat(dispatch("StAtE", admin)).isEqualTo(new CommandResult.Message("runtime: NEW"));
        assertThat(dispatch("STATUS", admin)).isEqualTo(new CommandResult.Message("runtime: NEW"));
        assertThat(dispatch("RePoSiToRiEs", admin))
                .isEqualTo(new CommandResult.Message("zeta\nalpha"));
    }

    @Test
    void shutdownRunsOnlyAfterAuthorization() {
        assertFailure(dispatch("shutdown", user(List.of())), CommandFailureCode.ACCESS_DENIED);
        assertThat(shutdown).isFalse();

        dispatch("ShUtDoWn", user(List.of(grant(AccessControl.GrantKey.SHUTDOWN))));

        assertThat(shutdown).isTrue();
    }

    @Test
    void anonymousAndOrdinaryUsersCannotRunAdministrativeCommands() {
        assertFailure(dispatch("state", SecurityContext.ANONYMOUS), CommandFailureCode.ACCESS_DENIED);
        assertFailure(dispatch("repositories", user(List.of())), CommandFailureCode.ACCESS_DENIED);
    }

    @Test
    void composesAuthenticatedCredentialCommandsUnderAuthKey() {
        assertThat(dispatch("/auth/key ls", user(List.of())))
                .isEqualTo(CommandResult.Rows.unqueried(
                        List.of(
                                CommandColumn.text("algorithm"),
                                CommandColumn.text("fingerprint"),
                                CommandColumn.bool("current")),
                        List.of(List.of(
                                CommandValue.text("ssh-rsa"),
                                CommandValue.text("SHA256:key"),
                                CommandValue.bool(false)))));
        assertThat(accessControl.listUsers).containsExactly("operator");
    }

    @Test
    void composesReadOnlyDomainTreeAlongsideLegacyAliases() {
        assertThat(commandTree.children().keySet())
                .containsExactly("auth", "repository", "organization", "session", "proxy", "system");
        assertThat(commandTree.actions().keySet())
                .containsExactly(
                        "whoami",
                        "issue-token",
                        "token",
                        "issue-launch-permit",
                        "state",
                        "status",
                        "repositories",
                        "shutdown");

        assertThat(dispatch("whoami", user(List.of())))
                .isEqualTo(new CommandResult.ObjectValue(Map.of(
                        "userId", CommandValue.text("operator"))));
        assertThat(dispatch("/repository ls", user(List.of(grant(
                AccessControl.GrantKey.REPOSITORY,
                "project")))))
                .isEqualTo(new CommandResult.Rows(
                        List.of(
                                CommandColumn.text("id"),
                                CommandColumn.text("name"),
                                CommandColumn.text("defaultHead"),
                                CommandColumn.number("refCount")),
                        List.of(List.of(
                                CommandValue.text("project"),
                                CommandValue.text("project"),
                                CommandValue.text("refs/heads/main"),
                                CommandValue.number(1))),
                        RowOutputFormat.AUTO,
                        Optional.of(new RowPage(1, 100, 1, OptionalInt.empty(), false))));
        assertFailure(dispatch("/organization ls", user(List.of())), CommandFailureCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void enablesQueriesOnlyOnComposedReadOnlyListCommands() {
        UserIdentity reader = user(List.of(grant(AccessControl.GrantKey.REPOSITORY, "project")));

        CommandResult.Rows queried = (CommandResult.Rows) dispatch(
                "/repository ls columns=id where refCount=1",
                reader);

        assertThat(queried.columns()).containsExactly(CommandColumn.text("id"));
        assertThat(queried.values()).containsExactly(List.of(CommandValue.text("project")));
        assertFailure(dispatch("repositories format=json", reader), CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(dispatch("/auth/key ls format=json", reader), CommandFailureCode.INVALID_ARGUMENTS);
    }

    @Test
    void loggingAuditPayloadCannotContainSensitiveResultValues() {
        CommandAuditRecord record = new CommandAuditRecord(
                "operator",
                "request",
                "session",
                "source",
                "/",
                "issue-token",
                Map.of("$0", "600"),
                "MESSAGE",
                "SUCCESS",
                1,
                Map.of());

        assertThat(Slf4jCommandAuditSink.format(record))
                .doesNotContain("issued-secret")
                .contains("action=issue-token", "result=MESSAGE/SUCCESS");
    }

    private CommandResult dispatch(String commandLine, UserIdentity identity) {
        SecurityContext securityContext = SecurityContext.createContext()
                .withUserIdentity(identity)
                .withRequestId("request");
        CommandContext context = new CommandContext(
                securityContext,
                "request",
                "session",
                "source",
                CommandPath.root(),
                CommandPresentation.plain(),
                CommandCancellation.never(),
                Map.of());
        return dispatcher.dispatch(new CommandRequest(commandLine, context));
    }

    private static UserIdentity user(List<AccessControl.Grant> grants) {
        return new InternalUserImpl("operator", grants);
    }

    private static AccessControl.Grant grant(AccessControl.GrantKey key) {
        return grant(key, TRUE_STRING);
    }

    private static AccessControl.Grant grant(AccessControl.GrantKey key, String value) {
        return new AccessControlDraft.Grant("test", new java.util.ArrayList<>())
                .addKey(key, value)
                .toAccessControl();
    }

    private static void assertFailure(CommandResult result, CommandFailureCode code) {
        assertThat(result).isInstanceOf(CommandResult.Failure.class);
        assertThat(((CommandResult.Failure) result).code()).isEqualTo(code);
    }

    private static AgentMessage.Hello hello(String authorization) {
        String[] lines = authorization.split("\n");
        return new AgentMessage.Hello(AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                new AgentLabel("local"), new AgentInstanceId(UUID.randomUUID()), "dev",
                new MachineInfo("local", "linux", "aarch64"), Map.of(),
                Optional.of(new AgentAuthentication(new AgentGeneration(Long.parseLong(lines[0])),
                        new AgentLaunchId(UUID.fromString(lines[1])), AgentAuthentication.Kind.LAUNCH_PERMIT,
                        ProtocolBytes.copyOf(Base64.getUrlDecoder().decode(lines[2])))));
    }

    private static final class PermitConnection implements AgentControlHandler.Connection {
        private boolean authenticated;
        private boolean closed;

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void handshakeComplete(AuthenticatedConnectionContext context) {
            authenticated = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class RecordingAccessControlService implements OrionAccessControlService {
        private final java.util.ArrayList<Long> expiries = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> listUsers = new java.util.ArrayList<>();

        @Override
        public SshCredentialListResult listSshCredentials(String userId) {
            listUsers.add(userId);
            return SshCredentialListResult.success(List.of(new SshCredential("ssh-rsa", "SHA256:key")));
        }

        @Override
        public TokenRefreshResult refreshToken(
                AuthenticationResult.Success renewalAuthority,
                long expiresInSeconds) {
            expiries.add(expiresInSeconds);
            return new TokenRefreshResult.Success("issued-secret", 1_000L);
        }

        @Override
        public void addKeyToUser(String username, String publicKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addSshKeysToUser(String username, List<String> publicKeys) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean userExists(String userName) {
            return false;
        }

        @Override
        public AuthenticationResult authenticateUser(String userName, byte[] credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenAuthenticationResult verifyToken(byte[] token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenIssueResult authenticateUserAndIssueToken(
                String userName,
                byte[] credential,
                long expiresInSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] accessControlConfigurationFile() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveAccessControlConfigurationFile(byte[] content) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RepositoryProvider implements NativeGitRepositoryProvider {
        @Override
        public List<String> repositoryNames() {
            return List.of("zeta", "alpha");
        }

        @Override
        public boolean exists(String repositoryName) {
            return false;
        }

        @Override
        public Result<NativeGitRepository> find(String repositoryName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<NativeGitRepository> create(String repositoryName) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class DomainSource implements OperatorDomainSource {
        @Override
        public OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> repositories() {
            return new OperatorQueryResult.AvailableSnapshot<>(List.of(new OperatorDomainViews.RepositoryView(
                    "project",
                    java.util.Optional.of("project"),
                    "project",
                    "refs/heads/main",
                    1,
                    java.util.Optional.empty())));
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.OrganizationView>> organizations() {
            return new OperatorQueryResult.Unavailable<>("organization");
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.UserView>> organizationUsers(
                String organizationId) {
            return new OperatorQueryResult.Unavailable<>("organization");
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> organizationRepositories(
                String organizationId) {
            return new OperatorQueryResult.Unavailable<>("organization");
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.SessionView>> sessions() {
            return new OperatorQueryResult.Unavailable<>("session");
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.ProxyView>> proxies() {
            return new OperatorQueryResult.Unavailable<>("proxy");
        }

        @Override
        public OperatorQueryResult<OperatorDomainViews.SystemResourceView> systemResources() {
            return new OperatorQueryResult.AvailableValue<>(
                    new OperatorDomainViews.SystemResourceView(1, 0, 0, 0));
        }

        @Override
        public OperatorQueryResult<List<OperatorDomainViews.ServiceView>> services() {
            return new OperatorQueryResult.AvailableSnapshot<>(List.of());
        }
    }
}
