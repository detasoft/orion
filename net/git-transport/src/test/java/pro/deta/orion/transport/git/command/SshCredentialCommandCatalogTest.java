package pro.deta.orion.transport.git.command;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.SshConnectionCredentials;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandColumn;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.DefaultCommandDispatcher;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SshCredentialCommandCatalogTest {
    private final RecordingService service = new RecordingService();
    private final DefaultCommandDispatcher dispatcher = new DefaultCommandDispatcher(
            new CommandLineParser(),
            new SshCredentialCommandCatalog(service).commandTree(),
            new pro.deta.orion.command.CommandRowQuery());

    @Test
    void listsOnlyAuthenticatedUsersSafeCredentialDescriptors() {
        service.listResult = SshCredentialListResult.success(List.of(
                new SshCredential("ssh-ed25519", "SHA256:other"),
                new SshCredential("ssh-rsa", "SHA256:current")));

        CommandResult result = dispatch(
                "/auth/key ls",
                context("alice", new SshConnectionCredentials("SHA256:current", List.of())));

        assertThat(result).isEqualTo(CommandResult.Rows.unqueried(
                List.of(
                        CommandColumn.text("algorithm"),
                        CommandColumn.text("fingerprint"),
                        CommandColumn.bool("current")),
                List.of(
                        List.of(
                                CommandValue.text("ssh-ed25519"),
                                CommandValue.text("SHA256:other"),
                                CommandValue.bool(false)),
                        List.of(
                                CommandValue.text("ssh-rsa"),
                                CommandValue.text("SHA256:current"),
                                CommandValue.bool(true)))));
        assertThat(service.listUsers).containsExactly("alice");

        assertFailure(dispatch("/auth/key ls", anonymous()), CommandFailureCode.ACCESS_DENIED);
        assertThat(service.listUsers).containsExactly("alice");
    }

    @Test
    void addsAllOrSelectedConnectionCandidatesWithoutLeakingKeyMaterial() {
        String first = publicKey();
        String second = publicKey();
        String firstFingerprint = fingerprint(first);
        service.updateResult = SshCredentialUpdateResult.success(List.of(), true);
        SecurityContext context = context(
                "alice",
                new SshConnectionCredentials(Optional.empty(), List.of(first, second)));

        assertThat(dispatch("/auth/key add candidates=all", context))
                .isEqualTo(new CommandResult.Message("SSH credentials added"));
        assertThat(service.addedKeys.removeFirst()).containsExactly(first, second);

        assertThat(dispatch("/auth/key add candidates=" + firstFingerprint, context))
                .isEqualTo(new CommandResult.Message("SSH credentials added"));
        assertThat(service.addedKeys.removeFirst()).containsExactly(first);

        var audit = dispatcher.describe(new CommandRequest(
                "/auth/key add key='" + first + "'",
                commandContext(context)));
        assertThat(audit.parameters()).containsEntry("key", "<redacted>");
        assertThat(audit.toString()).doesNotContain(first);
    }

    @Test
    void rejectsInvalidCandidateSelectionsBeforePersistence() {
        String first = publicKey();
        String second = publicKey();
        SecurityContext context = context(
                "alice",
                new SshConnectionCredentials(Optional.empty(), List.of(first, second)));

        assertFailure(dispatch("/auth/key add", context), CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(dispatch("/auth/key add candidates=all key='" + first + "'", context),
                CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(dispatch("/auth/key add candidates=missing", context), CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(dispatch("/auth/key add candidates=SHA256:", context), CommandFailureCode.INVALID_ARGUMENTS);
        assertFailure(dispatch(
                "/auth/key add candidates=" + fingerprint(first) + "," + fingerprint(first),
                context), CommandFailureCode.INVALID_ARGUMENTS);
        assertThat(service.addedKeys).isEmpty();
    }

    @Test
    void addsPastedKeyAsOpaqueInputAndReportsIdempotentSuccess() {
        String key = publicKey();
        service.updateResult = SshCredentialUpdateResult.success(List.of(), false);

        CommandResult result = dispatch(
                "/auth/key add key='" + key + "'",
                context("alice", SshConnectionCredentials.empty()));

        assertThat(result).isEqualTo(new CommandResult.Message("SSH credential already exists"));
        assertThat(service.addedKeys).containsExactly(List.of(key));
        assertThat(service.updateUsers).containsExactly("alice");
    }

    @Test
    void removesByPrefixWithForceAndExplainsCurrentSessionSemantics() {
        service.updateResult = SshCredentialUpdateResult.success(List.of(), true);
        SecurityContext context = context(
                "alice",
                new SshConnectionCredentials("SHA256:current", List.of()));

        assertThat(dispatch("/auth/key rm SHA256:current --force", context))
                .isEqualTo(new CommandResult.Message(
                        "SSH credential removed; this connection remains active"));
        assertThat(service.removals).containsExactly("alice|SHA256:current|true");

        service.updateResult = SshCredentialUpdateResult.failure(
                SshCredentialFailureCode.LAST_KEY_REQUIRES_FORCE,
                "requires force");
        CommandResult failure = dispatch("/auth/key rm SHA256:last", context);
        assertFailure(failure, CommandFailureCode.INVALID_ARGUMENTS);
        assertThat(((CommandResult.Failure) failure).message()).contains("last", "force");
    }

    @Test
    void mapsExpectedAclFailuresWithoutExposingCandidatesOrKeys() {
        service.listResult = SshCredentialListResult.failure(
                SshCredentialFailureCode.PERSISTENCE_FAILED,
                "private backend detail");
        CommandResult list = dispatch("/auth/key ls", context("alice", SshConnectionCredentials.empty()));
        assertFailure(list, CommandFailureCode.HANDLER_FAILED);
        assertThat(((CommandResult.Failure) list).message()).doesNotContain("private backend detail");

        service.updateResult = SshCredentialUpdateResult.failure(
                SshCredentialFailureCode.AMBIGUOUS_MATCH,
                "ambiguous",
                List.of("SHA256:one", "SHA256:two"),
                null);
        CommandResult remove = dispatch(
                "/auth/key rm SHA256:",
                context("alice", SshConnectionCredentials.empty()));
        assertFailure(remove, CommandFailureCode.INVALID_ARGUMENTS);
        assertThat(((CommandResult.Failure) remove).candidates())
                .containsExactly("SHA256:one", "SHA256:two");
    }

    private CommandResult dispatch(String command, SecurityContext securityContext) {
        return dispatcher.dispatch(new CommandRequest(command, commandContext(securityContext)));
    }

    private static CommandContext commandContext(SecurityContext securityContext) {
        return new CommandContext(
                securityContext,
                "request",
                "session",
                "source",
                CommandPath.root(),
                CommandPresentation.plain(),
                CommandCancellation.never(),
                Map.of());
    }

    private static SecurityContext context(String user, SshConnectionCredentials credentials) {
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl(user, List.of()))
                .withSshConnectionCredentials(credentials)
                .withRequestId("request");
    }

    private static SecurityContext anonymous() {
        return SecurityContext.createContext().withRequestId("request");
    }

    private static String publicKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return PublicKeyEntry.toString(generator.generateKeyPair().getPublic());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String fingerprint(String key) {
        return KeyUtils.getFingerPrint(pro.deta.orion.util.KeyUtils.readPublicKeyFromString(key));
    }

    private static void assertFailure(CommandResult result, CommandFailureCode code) {
        assertThat(result).isInstanceOf(CommandResult.Failure.class);
        assertThat(((CommandResult.Failure) result).code()).isEqualTo(code);
    }

    private static final class RecordingService implements OrionAccessControlService {
        private SshCredentialListResult listResult = SshCredentialListResult.success(List.of());
        private SshCredentialUpdateResult updateResult = SshCredentialUpdateResult.success(List.of(), true);
        private final List<String> listUsers = new ArrayList<>();
        private final List<String> updateUsers = new ArrayList<>();
        private final List<List<String>> addedKeys = new ArrayList<>();
        private final List<String> removals = new ArrayList<>();

        @Override
        public SshCredentialListResult listSshCredentials(String userId) {
            listUsers.add(userId);
            return listResult;
        }

        @Override
        public SshCredentialUpdateResult addSshCredentials(String userId, List<String> publicKeys) {
            updateUsers.add(userId);
            addedKeys.add(List.copyOf(publicKeys));
            return updateResult;
        }

        @Override
        public SshCredentialUpdateResult removeSshCredential(String userId, String prefix, boolean force) {
            updateUsers.add(userId);
            removals.add(userId + "|" + prefix + "|" + force);
            return updateResult;
        }

        @Override
        public void addKeyToUser(String username, String publicKey) {}

        @Override
        public void addSshKeysToUser(String username, List<String> publicKeys) {}

        @Override
        public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {}

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
        public TokenIssueResult issueTokenFor(UserIdentity userIdentity, long expiresInSeconds) {
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
}
