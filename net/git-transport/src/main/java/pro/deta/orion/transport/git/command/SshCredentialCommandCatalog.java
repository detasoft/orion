package pro.deta.orion.transport.git.command;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.sshd.common.config.keys.KeyUtils;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.SshConnectionCredentials;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.command.CommandDefinition;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandInvocation;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandResult;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Singleton
public final class SshCredentialCommandCatalog {
    private static final Set<String> NO_PARAMETERS = Set.of();
    private static final Set<String> ADD_PARAMETERS = Set.of("candidates", "key");
    private static final Set<String> KEY_PARAMETER = Set.of("key");
    private static final Set<String> REMOVE_PARAMETERS = Set.of("force");

    private final OrionAccessControlService accessControlService;

    @Inject
    public SshCredentialCommandCatalog(OrionAccessControlService accessControlService) {
        this.accessControlService = Objects.requireNonNull(accessControlService, "accessControlService");
    }

    public CommandNode commandTree() {
        CommandNode key = CommandNode.builder()
                .action(definition("ls", 0, 0, NO_PARAMETERS, NO_PARAMETERS, this::list))
                .action(definition("add", 0, 0, ADD_PARAMETERS, KEY_PARAMETER, this::add))
                .action(definition("rm", 1, 1, REMOVE_PARAMETERS, NO_PARAMETERS, this::remove))
                .build();
        return CommandNode.builder()
                .child("auth", CommandNode.builder().child("key", key).build())
                .build();
    }

    private CommandDefinition definition(
            String action,
            int minimumArguments,
            int maximumArguments,
            Set<String> namedParameters,
            Set<String> sensitiveParameters,
            Handler handler) {
        return new CommandDefinition(
                action,
                minimumArguments,
                maximumArguments,
                namedParameters,
                sensitiveParameters,
                NO_PARAMETERS,
                context -> true,
                this::authenticatedNamedUser,
                handler::handle);
    }

    private AccessDecision authenticatedNamedUser(CommandInvocation invocation) {
        SecurityContext context = invocation.context().securityContext();
        AccessDecision decision = SubjectAccessRules.authenticated().evaluate(context);
        if (!decision.allowed()) {
            return decision;
        }
        return context.getUserIdentity().getUserId().isBlank()
                ? AccessDecision.deny("named user is required")
                : decision;
    }

    private CommandResult list(CommandInvocation invocation) {
        String userId = userId(invocation);
        return switch (accessControlService.listSshCredentials(userId)) {
            case SshCredentialListResult.Success(var credentials) -> rows(invocation, credentials);
            case SshCredentialListResult.Failure(var code, var reason, var throwable) ->
                    failure(code, List.of());
        };
    }

    private static CommandResult.Rows rows(
            CommandInvocation invocation,
            List<SshCredential> credentials) {
        String current = connectionCredentials(invocation).authenticatedKeyFingerprint().orElse(null);
        List<List<String>> values = new ArrayList<>(credentials.size());
        for (SshCredential credential : credentials) {
            values.add(List.of(
                    credential.algorithm(),
                    credential.fingerprint(),
                    Boolean.toString(credential.fingerprint().equals(current))));
        }
        return new CommandResult.Rows(List.of("algorithm", "fingerprint", "current"), values);
    }

    private CommandResult add(CommandInvocation invocation) {
        Map<String, String> parameters = invocation.arguments().named();
        boolean hasCandidates = parameters.containsKey("candidates");
        boolean hasKey = parameters.containsKey("key");
        if (hasCandidates == hasKey) {
            return invalid("Specify exactly one of candidates or key");
        }
        List<String> publicKeys = hasKey
                ? List.of(parameters.get("key"))
                : selectedCandidates(invocation, parameters.get("candidates"));
        if (publicKeys == null || publicKeys.isEmpty()) {
            return invalid("Candidate selection is invalid");
        }
        return switch (accessControlService.addSshCredentials(userId(invocation), publicKeys)) {
            case SshCredentialUpdateResult.Success(var credentials, var changed) -> new CommandResult.Message(
                    changed ? "SSH credentials added" : "SSH credential already exists");
            case SshCredentialUpdateResult.Failure(var code, var reason, var candidates, var throwable) ->
                    failure(code, safeCandidates(code, candidates));
        };
    }

    private static List<String> selectedCandidates(CommandInvocation invocation, String selection) {
        if (selection == null || selection.isBlank()) {
            return null;
        }
        List<Candidate> available = candidates(connectionCredentials(invocation));
        if (available == null || available.isEmpty()) {
            return null;
        }
        if ("all".equalsIgnoreCase(selection.trim())) {
            List<String> all = new ArrayList<>(available.size());
            for (Candidate candidate : available) {
                all.add(candidate.publicKey());
            }
            return all;
        }
        String[] selectors = selection.split(",", -1);
        Set<String> selectedFingerprints = new LinkedHashSet<>();
        List<String> selectedKeys = new ArrayList<>(selectors.length);
        for (String rawSelector : selectors) {
            String selector = rawSelector.trim();
            if (selector.isEmpty()) {
                return null;
            }
            Candidate match = null;
            for (Candidate candidate : available) {
                if (candidate.fingerprint().startsWith(selector)) {
                    if (match != null) {
                        return null;
                    }
                    match = candidate;
                }
            }
            if (match == null || !selectedFingerprints.add(match.fingerprint())) {
                return null;
            }
            selectedKeys.add(match.publicKey());
        }
        return selectedKeys;
    }

    private static List<Candidate> candidates(SshConnectionCredentials credentials) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        try {
            for (String serialized : credentials.candidatePublicKeys()) {
                PublicKey key = pro.deta.orion.util.KeyUtils.readPublicKeyFromString(serialized);
                String fingerprint = KeyUtils.getFingerPrint(key);
                unique.putIfAbsent(fingerprint, new Candidate(fingerprint, serialized));
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return List.copyOf(unique.values());
    }

    private CommandResult remove(CommandInvocation invocation) {
        String prefix = invocation.arguments().positional().getFirst().trim();
        if (prefix.isEmpty()) {
            return invalid("SSH credential fingerprint prefix is required");
        }
        boolean force = Boolean.parseBoolean(invocation.arguments().named().getOrDefault("force", "false"));
        return switch (accessControlService.removeSshCredential(userId(invocation), prefix, force)) {
            case SshCredentialUpdateResult.Success(var credentials, var changed) -> {
                boolean current = connectionCredentials(invocation)
                        .authenticatedKeyFingerprint()
                        .filter(fingerprint -> fingerprint.startsWith(prefix))
                        .isPresent();
                yield new CommandResult.Message(current
                        ? "SSH credential removed; this connection remains active"
                        : "SSH credential removed");
            }
            case SshCredentialUpdateResult.Failure(var code, var reason, var candidates, var throwable) ->
                    failure(code, safeCandidates(code, candidates));
        };
    }

    private static List<String> safeCandidates(SshCredentialFailureCode code, List<String> candidates) {
        return code == SshCredentialFailureCode.AMBIGUOUS_MATCH ? candidates : List.of();
    }

    private static CommandResult.Failure failure(SshCredentialFailureCode code, List<String> candidates) {
        return switch (code) {
            case USER_NOT_FOUND -> failure(
                    CommandFailureCode.ACCESS_DENIED,
                    "Authenticated user is unavailable");
            case INVALID_KEY -> invalid("SSH public key is invalid");
            case INVALID_STORED_KEY -> failure(
                    CommandFailureCode.HANDLER_FAILED,
                    "Stored SSH credential is invalid");
            case MISSING_MATCH -> invalid("No SSH credential matches the fingerprint prefix");
            case AMBIGUOUS_MATCH -> new CommandResult.Failure(
                    CommandFailureCode.INVALID_ARGUMENTS,
                    "SSH credential fingerprint prefix is ambiguous",
                    candidates);
            case LAST_KEY_REQUIRES_FORCE -> invalid("Removing the last SSH credential requires --force");
            case ROOT_LOCKED -> failure(CommandFailureCode.ACCESS_DENIED, "Root authentication is locked");
            case CONCURRENT_UPDATE -> failure(
                    CommandFailureCode.HANDLER_FAILED,
                    "Access control changed concurrently; retry the command");
            case PERSISTENCE_FAILED -> failure(
                    CommandFailureCode.HANDLER_FAILED,
                    "SSH credential update failed");
        };
    }

    private static CommandResult.Failure invalid(String message) {
        return failure(CommandFailureCode.INVALID_ARGUMENTS, message);
    }

    private static CommandResult.Failure failure(CommandFailureCode code, String message) {
        return new CommandResult.Failure(code, message, List.of());
    }

    private static String userId(CommandInvocation invocation) {
        return invocation.context().securityContext().getUserIdentity().getUserId();
    }

    private static SshConnectionCredentials connectionCredentials(CommandInvocation invocation) {
        return invocation.context().securityContext().getSshConnectionCredentials();
    }

    private record Candidate(String fingerprint, String publicKey) {}

    @FunctionalInterface
    private interface Handler {
        CommandResult handle(CommandInvocation invocation);
    }
}
