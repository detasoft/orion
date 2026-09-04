package pro.deta.orion.transport.git.auth;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.SshConnectionCredentials;
import pro.deta.orion.auth.UserIdentity;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static pro.deta.orion.transport.git.GitSshTransportService.SSH_AUTHENTICATED_USER;

@Singleton
public final class OrionSshAuthenticator implements PublickeyAuthenticator {
    private static final String GIT_USERNAME = "git";
    private static final AttributeRepository.AttributeKey<PendingPublicKeyAttempt> PENDING_PUBLIC_KEY =
            new AttributeRepository.AttributeKey<>();
    private static final AttributeRepository.AttributeKey<LinkedHashMap<String, PublicKey>> PROVED_PUBLIC_KEYS =
            new AttributeRepository.AttributeKey<>();
    private static final AttributeRepository.AttributeKey<String> AUTHENTICATED_KEY_FINGERPRINT =
            new AttributeRepository.AttributeKey<>();
    private static final AttributeRepository.AttributeKey<Boolean> KEYBOARD_INTERACTIVE_FAILED =
            new AttributeRepository.AttributeKey<>();

    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionSshAuthenticator(OrionAccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    @Override
    public boolean authenticate(String username, PublicKey key, ServerSession session) {
        abandonPublicKeyAttempt(session);
        AuthenticationResult result = GIT_USERNAME.equalsIgnoreCase(username)
                ? accessControlService.authenticateGitSshKey(key.getEncoded())
                : accessControlService.authenticateSshUser(username, key.getEncoded());
        if (result instanceof AuthenticationResult.Success(var identity)) {
            session.setAttribute(PENDING_PUBLIC_KEY, PendingPublicKeyAttempt.authorized(key, identity));
            return true;
        }
        if (!GIT_USERNAME.equalsIgnoreCase(username) && accessControlService.userExists(username)) {
            session.setAttribute(PENDING_PUBLIC_KEY, PendingPublicKeyAttempt.candidate(key));
            return true;
        }
        return false;
    }

    boolean completePublicKeyAttempt(ServerSession session) {
        PendingPublicKeyAttempt attempt = session.removeAttribute(PENDING_PUBLIC_KEY);
        if (attempt == null) {
            return false;
        }
        if (attempt.identity() != null) {
            session.setAttribute(AUTHENTICATED_KEY_FINGERPRINT, fingerprint(attempt.key()));
            session.setAttribute(SSH_AUTHENTICATED_USER, attempt.identity());
            return true;
        }

        LinkedHashMap<String, PublicKey> candidates = session.computeAttributeIfAbsent(
                PROVED_PUBLIC_KEYS,
                ignored -> new LinkedHashMap<>());
        candidates.putIfAbsent(fingerprint(attempt.key()), attempt.key());
        return false;
    }

    void abandonPublicKeyAttempt(ServerSession session) {
        session.removeAttribute(PENDING_PUBLIC_KEY);
    }

    boolean allowsKeyboardInteractive(ServerSession session, String username) {
        return !Boolean.TRUE.equals(session.getAttribute(KEYBOARD_INTERACTIVE_FAILED))
                && !GIT_USERNAME.equalsIgnoreCase(username)
                && accessControlService.userExists(username);
    }

    InteractiveChallenge passwordChallenge() {
        InteractiveChallenge challenge = new InteractiveChallenge();
        challenge.setInteractionName("Orion authentication");
        challenge.setInteractionInstruction("");
        challenge.setLanguageTag("");
        challenge.addPrompt("Orion password: ", false);
        return challenge;
    }

    PasswordAuthentication authenticatePassword(ServerSession session, String username, String password) {
        if (!allowsKeyboardInteractive(session, username) || password == null) {
            return null;
        }
        byte[] credential = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            return switch (accessControlService.authenticateSshKeyEnrollment(username, credential)) {
                case SshKeyEnrollmentAuthentication.Success(var identity, var rootRecoveryGeneration) ->
                        new PasswordAuthentication(identity, provedCandidates(session), rootRecoveryGeneration.orElse(null));
                case SshKeyEnrollmentAuthentication.Failure ignored -> null;
            };
        } finally {
            Arrays.fill(credential, (byte) 0);
        }
    }

    InteractiveChallenge selectionChallenge(List<PublicKey> candidates) {
        InteractiveChallenge challenge = new InteractiveChallenge();
        challenge.setInteractionName("Orion SSH key enrollment");
        challenge.setInteractionInstruction(candidateInstruction(candidates));
        challenge.setLanguageTag("");
        challenge.addPrompt("Keys (`all`, numbers, or OpenSSH key): ", true);
        return challenge;
    }

    boolean completePasswordAuthentication(
            ServerSession session,
            String username,
            PasswordAuthentication authentication,
            String selection) {
        if (!allowsKeyboardInteractive(session, username) || authentication == null) {
            return false;
        }
        if (authentication.candidates().isEmpty() && authentication.rootRecoveryGeneration() == null) {
            session.removeAttribute(AUTHENTICATED_KEY_FINGERPRINT);
            session.setAttribute(SSH_AUTHENTICATED_USER, authentication.identity());
            return true;
        }
        List<String> selectedKeys = selectedKeys(session, authentication.candidates(), selection);
        if (selectedKeys == null || selectedKeys.isEmpty()) {
            return false;
        }
        if (authentication.rootRecoveryGeneration() == null) {
            accessControlService.addSshKeysToUser(username, selectedKeys);
        } else {
            RootSshKeyEnrollmentSession.begin(
                    session,
                    authentication.rootRecoveryGeneration(),
                    selectedKeys);
        }
        session.removeAttribute(AUTHENTICATED_KEY_FINGERPRINT);
        session.setAttribute(SSH_AUTHENTICATED_USER, authentication.identity());
        return true;
    }

    boolean failKeyboardInteractive(ServerSession session) {
        session.setAttribute(KEYBOARD_INTERACTIVE_FAILED, true);
        return false;
    }

    private List<String> selectedKeys(ServerSession session, List<PublicKey> candidates, String selection) {
        if (selection == null || selection.isBlank()) {
            return null;
        }
        String trimmed = selection.trim();
        if ("all".equalsIgnoreCase(trimmed)) {
            return serializeKeys(candidates);
        }
        if (isNumericSelection(trimmed)) {
            return selectCandidateKeys(candidates, trimmed);
        }
        return parsePastedKey(session, trimmed);
    }

    private static boolean isNumericSelection(String selection) {
        char first = selection.charAt(0);
        return Character.isDigit(first) || first == ',';
    }

    private static List<String> selectCandidateKeys(List<PublicKey> candidates, String selection) {
        String[] parts = selection.split(",", -1);
        Set<Integer> indexes = new LinkedHashSet<>();
        for (String part : parts) {
            int index;
            try {
                index = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (index < 1 || index > candidates.size() || !indexes.add(index)) {
                return null;
            }
        }

        List<String> selected = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            selected.add(PublicKeyEntry.toString(candidates.get(index - 1)));
        }
        return selected;
    }

    private static List<String> parsePastedKey(ServerSession session, String source) {
        try {
            PublicKeyEntry entry = PublicKeyEntry.parsePublicKeyEntry(source);
            if (entry == null) {
                return null;
            }
            PublicKey key = entry.resolvePublicKey(session, Map.of(), PublicKeyEntryResolver.IGNORING);
            return key == null ? null : List.of(PublicKeyEntry.toString(key));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> serializeKeys(List<PublicKey> keys) {
        List<String> serialized = new ArrayList<>(keys.size());
        for (PublicKey key : keys) {
            serialized.add(PublicKeyEntry.toString(key));
        }
        return serialized;
    }

    private static String candidateInstruction(List<PublicKey> candidates) {
        if (candidates.isEmpty()) {
            return "No proved candidate keys. Paste one OpenSSH public key.";
        }
        StringBuilder instruction = new StringBuilder("Proved candidate keys:\n");
        for (int index = 0; index < candidates.size(); index++) {
            PublicKey key = candidates.get(index);
            instruction.append(index + 1)
                    .append(". ")
                    .append(org.apache.sshd.common.config.keys.KeyUtils.getKeyType(key))
                    .append(' ')
                    .append(fingerprint(key))
                    .append('\n');
        }
        return instruction.toString();
    }

    private static List<PublicKey> provedCandidates(ServerSession session) {
        Map<String, PublicKey> candidates = session.getAttribute(PROVED_PUBLIC_KEYS);
        return candidates == null ? List.of() : List.copyOf(candidates.values());
    }

    public static SshConnectionCredentials connectionCredentials(ServerSession session) {
        String authenticatedFingerprint = session.getAttribute(AUTHENTICATED_KEY_FINGERPRINT);
        return new SshConnectionCredentials(authenticatedFingerprint, serializeKeys(provedCandidates(session)));
    }

    private static String fingerprint(PublicKey key) {
        return org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(key);
    }

    private record PendingPublicKeyAttempt(PublicKey key, UserIdentity identity) {
        private static PendingPublicKeyAttempt authorized(PublicKey key, UserIdentity identity) {
            return new PendingPublicKeyAttempt(key, identity);
        }

        private static PendingPublicKeyAttempt candidate(PublicKey key) {
            return new PendingPublicKeyAttempt(key, null);
        }
    }

    record PasswordAuthentication(
            UserIdentity identity,
            List<PublicKey> candidates,
            String rootRecoveryGeneration) {
        PasswordAuthentication {
            candidates = List.copyOf(candidates);
        }
    }
}
