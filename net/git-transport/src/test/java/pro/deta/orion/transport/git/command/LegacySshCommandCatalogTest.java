package pro.deta.orion.transport.git.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandDispatcher;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.schema.acl.AccessControl.TRUE_STRING;

class LegacySshCommandCatalogTest {
    private final RecordingAccessControlService accessControl = new RecordingAccessControlService();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final CommandDispatcher dispatcher = new DefaultCommandDispatcher(
            new CommandLineParser(),
            new LegacySshCommandCatalog(
                    accessControl,
                    new AggregateStateMachine(StateMachineDefinition.define().name("runtime").build()),
                    new RepositoryProvider(),
                    () -> shutdown.set(true))
                    .commandTree());

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
        return new AccessControlDraft.Grant("test", new java.util.ArrayList<>())
                .addKey(key, TRUE_STRING)
                .toAccessControl();
    }

    private static void assertFailure(CommandResult result, CommandFailureCode code) {
        assertThat(result).isInstanceOf(CommandResult.Failure.class);
        assertThat(((CommandResult.Failure) result).code()).isEqualTo(code);
    }

    private static final class RecordingAccessControlService implements OrionAccessControlService {
        private final java.util.ArrayList<Long> expiries = new java.util.ArrayList<>();

        @Override
        public TokenIssueResult issueTokenFor(UserIdentity userIdentity, long expiresInSeconds) {
            expiries.add(expiresInSeconds);
            return new TokenIssueResult.Success("issued-secret", 1_000L);
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
        public AuthenticationResult authenticateToken(byte[] token) {
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
}
