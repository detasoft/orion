package pro.deta.orion.acl;

import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.config.OrionConfigurationModification;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AccessControlValidationException;
import pro.deta.orion.auth.SshKeyEnrollmentResult;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OidcProvider;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.Modification;
import java.security.PublicKey;
import java.util.*;
import java.util.function.UnaryOperator;
import static pro.deta.orion.schema.acl.CredentialType.OPENSSH_PUBLIC_KEY;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.ARGON2;

/** Edits ACL data through the same configuration candidate and completion boundary. */
@Slf4j
public class AccessControlAccess implements Modification {
    static final String ROOT_USER_ID = "root";
    static final String ROOT_AUTH_GENERATION_PREFIX = "root-auth-generation:";
    static final String ROOT_LOCKED_GENERATION_PREFIX = "root-auth-locked:";
    private final OrionConfigurationModification edit;
    private final OrionPasswordHashingService orionPasswordHashingService;

    public AccessControlAccess(OrionConfigurationModification edit, OrionPasswordHashingService passwordHashing) {
        this.edit = Objects.requireNonNull(edit, "configuration edit");
        this.orionPasswordHashingService = passwordHashing;
    }

    public OrionDocument document() {
        return edit.document();
    }

    public AccessControlAccess update(UnaryOperator<OrionDocument> update) {
        edit.update(update);
        return this;
    }

    @Override
    public void apply() {
        edit.apply();
    }

    @Override
    public void discard() {
        edit.discard();
    }

    public SshCredentialUpdateResult addSshCredentials(String userId, List<String> publicKeys) {
        List<PublicKey> parsedKeys;
        try {
            parsedKeys = parseAndDeduplicatePublicKeys(publicKeys);
        } catch (RuntimeException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.INVALID_KEY,
                    "SSH public key is invalid",
                    List.of(),
                    e);
        }
        if (userId == null || userId.isBlank()) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.USER_NOT_FOUND,
                    "User is not available");
        }

        return stageSshCredentials(userId, parsedKeys);
    }

    private SshCredentialUpdateResult stageSshCredentials(
            String userId,
            List<PublicKey> publicKeys) {
        try {
            AccessControl acl = edit.document().system().accessControl();
            User user = findUser(acl, userId);
            if (user == null) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.USER_NOT_FOUND,
                        "User is not available");
            }
            if (isLockedRoot(user)) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.ROOT_LOCKED,
                        "Root SSH credentials are locked");
            }
            ParsedSshCredentials existing = sshCredentials(user);
            List<Credential> credentials = addMissingPublicKeys(user, existing, publicKeys);
            if (credentials == null) {
                return SshCredentialUpdateResult.success(existing.descriptors(), false);
            }
            User updated = withCredentials(user, credentials);
            edit.update(document -> document.replaceAccessControl(replaceUser(acl, user, updated)));
            return SshCredentialUpdateResult.success(sshCredentials(updated).descriptors(), true);
        } catch (InvalidStoredSshKeyException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.INVALID_STORED_KEY,
                    "Stored SSH credential is invalid",
                    List.of(),
                    e);
        } catch (OrionConfigurationConcurrentUpdateException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.CONCURRENT_UPDATE,
                    "Access control changed concurrently",
                    List.of(),
                    e);
        } catch (RuntimeException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.PERSISTENCE_FAILED,
                    "Cannot save SSH credentials",
                    List.of(),
                    e);
        }
    }

    public SshCredentialUpdateResult removeSshCredential(
            String userId,
            String fingerprintPrefix,
            boolean force) {
        if (userId == null || userId.isBlank()) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.USER_NOT_FOUND,
                    "User is not available");
        }
        String prefix = Objects.requireNonNullElse(fingerprintPrefix, "").trim();
        if (prefix.isEmpty()) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.MISSING_MATCH,
                    "SSH credential fingerprint prefix is required");
        }
        return stageSshCredentialRemoval(userId, prefix, force);
    }

    private SshCredentialUpdateResult stageSshCredentialRemoval(
            String userId,
            String fingerprintPrefix,
            boolean force) {
        try {
            AccessControl acl = edit.document().system().accessControl();
            User user = findUser(acl, userId);
            if (user == null) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.USER_NOT_FOUND,
                        "User is not available");
            }
            ParsedSshCredentials existing = sshCredentials(user);
            List<ParsedSshCredential> matches = new ArrayList<>();
            for (ParsedSshCredential credential : existing.byEncodedKey().values()) {
                if (credential.descriptor().fingerprint().startsWith(fingerprintPrefix)) {
                    matches.add(credential);
                }
            }
            if (matches.isEmpty()) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.MISSING_MATCH,
                        "No SSH credential matches the fingerprint prefix");
            }
            if (matches.size() > 1) {
                List<String> candidates = new ArrayList<>();
                for (ParsedSshCredential match : matches) {
                    candidates.add(match.descriptor().fingerprint());
                }
                candidates.sort(String::compareTo);
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.AMBIGUOUS_MATCH,
                        "SSH credential fingerprint prefix is ambiguous",
                        candidates,
                        null);
            }
            if (existing.byEncodedKey().size() == 1 && !force) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.LAST_KEY_REQUIRES_FORCE,
                        "Removing the last SSH credential requires force");
            }

            List<Credential> credentials = new ArrayList<>(user.credentials());
            removePublicKey(credentials, matches.getFirst().publicKey());
            if (existing.byEncodedKey().size() == 1 && isRoot(user.id())) {
                lockRoot(credentials);
            }
            User updated = withCredentials(user, credentials);
            edit.update(document -> document.replaceAccessControl(replaceUser(acl, user, updated)));
            return SshCredentialUpdateResult.success(sshCredentials(updated).descriptors(), true);
        } catch (InvalidStoredSshKeyException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.INVALID_STORED_KEY,
                    "Stored SSH credential is invalid",
                    List.of(),
                    e);
        } catch (OrionConfigurationConcurrentUpdateException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.CONCURRENT_UPDATE,
                    "Access control changed concurrently",
                    List.of(),
                    e);
        } catch (RuntimeException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.PERSISTENCE_FAILED,
                    "Cannot remove SSH credential",
                    List.of(),
                    e);
        }
    }

    public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
        validateUserUpdate(userUpdate);
        AccessControl acl = edit.document().system().accessControl();
        User existing = findUser(acl, userUpdate.id());
        List<User> users = new ArrayList<>(acl.users());
        if (existing != null) {
            users.remove(existing);
        }
        users.add(userFrom(userUpdate));
        AccessControl updated = new AccessControl(users, acl.roles(), acl.grants());
        edit.update(document -> document.replaceAccessControl(updated));
    }

    public SshKeyEnrollmentResult completeRootSshKeyEnrollment(
            String expectedGeneration,
            List<String> publicKeys) {
        if (expectedGeneration == null || expectedGeneration.isBlank()) {
            return SshKeyEnrollmentResult.failure("key enrollment failed");
        }
        List<PublicKey> parsedKeys;
        try {
            parsedKeys = parseAndDeduplicatePublicKeys(publicKeys);
        } catch (IllegalArgumentException e) {
            return SshKeyEnrollmentResult.failure("key enrollment failed", e);
        }

        return stageRootSshKeyEnrollment(expectedGeneration, parsedKeys);
    }

    private SshKeyEnrollmentResult stageRootSshKeyEnrollment(
            String expectedGeneration,
            List<PublicKey> publicKeys) {
        try {
            AccessControl acl = edit.document().system().accessControl();
            List<User> roots = rootUsers(acl);
            if (roots.size() != 1) {
                return SshKeyEnrollmentResult.failure("key enrollment failed");
            }
            User root = roots.getFirst();
            if (!expectedGeneration.equals(rootRecoveryGeneration(root))) {
                return SshKeyEnrollmentResult.failure("key enrollment failed");
            }

            List<Credential> credentials = new ArrayList<>();
            for (PublicKey publicKey : publicKeys) {
                credentials.add(new Credential(
                        OPENSSH_PUBLIC_KEY,
                        generationKeyId(expectedGeneration),
                        PublicKeyEntry.toString(publicKey)));
            }
            User updated = withCredentials(root, credentials);
            edit.update(document -> document.replaceAccessControl(replaceUser(acl, root, updated)));
            return SshKeyEnrollmentResult.success();
        } catch (RuntimeException e) {
            return SshKeyEnrollmentResult.failure("key enrollment failed", e);
        }
    }

    private User userFrom(AccessControlUserUpdate userUpdate) {
        List<Credential> credentials = new ArrayList<>();
        List<Grant> grants = new ArrayList<>();
        for (AccessControlCredentialUpdate credential : userUpdate.credentials()) {
            credentials.add(new Credential(
                    credential.type(), credential.keyId(), credential.value()));
        }
        for (AccessControlRepositoryGrantUpdate repositoryGrant : userUpdate.repositories()) {
            grants.add(repositoryGrant(userUpdate.id(), repositoryGrant));
        }
        return new User(userUpdate.id(), null, null, userUpdate.email(),
                credentials, List.of(), grants);
    }

    private Grant repositoryGrant(String userId,
            AccessControlRepositoryGrantUpdate repositoryGrant) {
        List<GrantExpression> expressions = new ArrayList<>();
        expressions.add(new GrantExpression(
                GrantKey.REPOSITORY, repositoryGrant.repository()));
        expressions.add(new GrantExpression(
                GrantKey.BRANCH, repositoryGrant.branch()));
        if (repositoryGrant.read()) {
            expressions.add(new GrantExpression(
                    GrantKey.READ, "true"));
        }
        if (repositoryGrant.readWrite()) {
            expressions.add(new GrantExpression(
                    GrantKey.READ_WRITE, "true"));
        }
        if (repositoryGrant.create()) {
            expressions.add(new GrantExpression(
                    GrantKey.CREATE, "true"));
        }
        if (repositoryGrant.force()) {
            expressions.add(new GrantExpression(
                    GrantKey.FORCE, "true"));
        }
        return new Grant(
                repositoryGrantId(userId, repositoryGrant.repository()), expressions);
    }

    private String repositoryGrantId(String userId, String repository) {
        return "REPOSITORY_" + safeGrantIdPart(userId) + "_" + safeGrantIdPart(repository);
    }

    private String safeGrantIdPart(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void validateUserUpdate(AccessControlUserUpdate userUpdate) {
        if (userUpdate == null) {
            throw new AccessControlValidationException("User update is required");
        }
        if (userUpdate.id() == null || userUpdate.id().isBlank()) {
            throw new AccessControlValidationException("User id is required");
        }
        for (AccessControlCredentialUpdate credential : userUpdate.credentials()) {
            if (credential.type() == null) {
                throw new AccessControlValidationException("Credential type is required");
            }
            if (credential.type() == CredentialType.JWT_SIGNING_PUBLIC_KEY
                    && (credential.keyId() == null || credential.keyId().isBlank())) {
                throw new AccessControlValidationException("JWT signing key id is required");
            }
            if (credential.type() == CredentialType.OIDC_SUBJECT) {
                if (credential.keyId() == null || credential.keyId().isBlank()) {
                    throw new AccessControlValidationException("OIDC issuer is required");
                }
                try {
                    new OidcProvider("validation", java.net.URI.create(credential.keyId()), "validation",
                            "validation", OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0);
                } catch (IllegalArgumentException invalid) {
                    throw new AccessControlValidationException("Invalid OIDC issuer");
                }
            }
            if (credential.value() == null || credential.value().isBlank()) {
                throw new AccessControlValidationException("Credential value is required");
            }
        }
        for (AccessControlRepositoryGrantUpdate repositoryGrant : userUpdate.repositories()) {
            if (repositoryGrant.repository() == null || repositoryGrant.repository().isBlank()) {
                throw new AccessControlValidationException("Repository name is required");
            }
        }
    }

    private List<PublicKey> parseAndDeduplicatePublicKeys(List<String> publicKeys) {
        if (publicKeys == null || publicKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one SSH public key is required");
        }
        Map<String, PublicKey> parsed = new LinkedHashMap<>();
        for (String publicKey : publicKeys) {
            PublicKey key;
            try {
                key = KeyUtils.readPublicKeyFromString(publicKey);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid SSH public key", e);
            }
            parsed.putIfAbsent(Base64.getEncoder().encodeToString(key.getEncoded()), key);
        }
        return List.copyOf(parsed.values());
    }

    private List<Credential> addMissingPublicKeys(
            User user,
            ParsedSshCredentials existing,
            List<PublicKey> publicKeys) {
        boolean changed = false;
        List<Credential> credentials = new ArrayList<>(user.credentials());
        String generation = rootAuthenticationGeneration(user);
        Set<String> known = new HashSet<>(existing.byEncodedKey().keySet());
        for (PublicKey publicKey : publicKeys) {
            String encoded = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            if (!known.add(encoded)) {
                continue;
            }
            String canonical = PublicKeyEntry.toString(publicKey);
            if (generation == null) {
                credentials.add(new Credential(OPENSSH_PUBLIC_KEY, canonical));
            } else {
                credentials.add(new Credential(OPENSSH_PUBLIC_KEY,
                        generationKeyId(generation), canonical));
            }
            changed = true;
        }
        return changed ? credentials : null;
    }

    private void removePublicKey(List<Credential> credentials, PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        credentials.removeIf(credential -> credential.type() == OPENSSH_PUBLIC_KEY
                && publicKeysAreEqual(credential.value(), encoded));
    }

    private void lockRoot(List<Credential> credentials) {
        char[] markerSecret = orionPasswordHashingService.generateRandomString(32);
        try {
            String markerHash = orionPasswordHashingService.calculateHash(ARGON2, markerSecret);
            credentials.add(new Credential(
                    CredentialType.ARGON2,
                    ROOT_LOCKED_GENERATION_PREFIX + UUID.randomUUID(),
                    markerHash));
        } finally {
            Arrays.fill(markerSecret, '\0');
        }
    }

    static List<User> rootUsers(AccessControl acl) {
        List<User> roots = new ArrayList<>();
        for (User user : acl.users()) {
            if (isRoot(user.id())) {
                roots.add(user);
            }
        }
        return List.copyOf(roots);
    }

    static String generationKeyId(String generation) {
        return ROOT_AUTH_GENERATION_PREFIX + generation;
    }

    static String generationFromKeyId(String keyId) {
        if (keyId == null || !keyId.startsWith(ROOT_AUTH_GENERATION_PREFIX)) {
            return null;
        }
        String generation = keyId.substring(ROOT_AUTH_GENERATION_PREFIX.length());
        return generation.isBlank() ? null : generation;
    }

    static String rootRecoveryGeneration(User user) {
        if (!isRoot(user.id())) return null;
        List<Credential> credentials = primaryCredentials(user);
        if (credentials.size() != 1) {
            return null;
        }
        Credential credential = credentials.getFirst();
        return credential.type() == CredentialType.ARGON2
                ? generationFromKeyId(credential.keyId())
                : null;
    }

    static String rootAuthenticationGeneration(User user) {
        if (!isRoot(user.id()) || user.credentials().isEmpty()) {
            return null;
        }
        List<Credential> authenticationCredentials = primaryCredentials(user);
        CredentialType expectedType = authenticationCredentials.size() == 1
                && authenticationCredentials.getFirst().type() == CredentialType.ARGON2
                ? CredentialType.ARGON2
                : OPENSSH_PUBLIC_KEY;
        String generation = null;
        for (Credential credential : authenticationCredentials) {
            if (credential.type() != expectedType) {
                return null;
            }
            String candidate = generationFromKeyId(credential.keyId());
            if (candidate == null || generation != null && !generation.equals(candidate)) {
                return null;
            }
            generation = candidate;
        }
        return generation;
    }

    static List<Credential> primaryCredentials(User user) {
        List<Credential> credentials = new ArrayList<>();
        for (Credential credential : user.credentials()) {
            if (credential.type() != CredentialType.OIDC_SUBJECT) credentials.add(credential);
        }
        return credentials;
    }

    static boolean isGenerationAwareRoot(User user) {
        if (!isRoot(user.id())) {
            return false;
        }
        for (Credential credential : user.credentials()) {
            if (credential.keyId() != null
                    && (credential.keyId().startsWith(ROOT_AUTH_GENERATION_PREFIX)
                    || credential.keyId().startsWith(ROOT_LOCKED_GENERATION_PREFIX))) {
                return true;
            }
        }
        return false;
    }

    static boolean isRoot(String userId) {
        return userId != null && ROOT_USER_ID.equalsIgnoreCase(userId);
    }

    static User findUser(AccessControl acl, String userId) {
        List<User> matches = findUsers(Objects.requireNonNull(acl), userId);
        if (matches.size() > 1) {
            throw new IllegalStateException("More than one user matches the requested id");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    static User withCredentials(
            User user, List<Credential> credentials) {
        return new User(user.id(), user.first(), user.last(), user.email(),
                credentials, user.roles(), user.grants());
    }

    static AccessControl replaceUser(
            AccessControl acl, User current, User replacement) {
        List<User> users = new ArrayList<>(acl.users());
        users.set(users.indexOf(current), replacement);
        return new AccessControl(users, acl.roles(), acl.grants());
    }

    static ParsedSshCredentials sshCredentials(User user) {
        Map<String, ParsedSshCredential> credentials = new LinkedHashMap<>();
        for (Credential credential : user.credentials()) {
            if (credential.type() != OPENSSH_PUBLIC_KEY) {
                continue;
            }
            PublicKey publicKey;
            try {
                publicKey = KeyUtils.readPublicKeyFromString(credential.value());
            } catch (RuntimeException e) {
                throw new InvalidStoredSshKeyException(e);
            }
            String encoded = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            credentials.putIfAbsent(encoded, new ParsedSshCredential(
                    publicKey,
                    new SshCredential(
                            org.apache.sshd.common.config.keys.KeyUtils.getKeyType(publicKey),
                            org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(publicKey))));
        }
        List<SshCredential> descriptors = new ArrayList<>();
        for (ParsedSshCredential credential : credentials.values()) {
            descriptors.add(credential.descriptor());
        }
        descriptors.sort(Comparator.comparing(SshCredential::fingerprint));
        return new ParsedSshCredentials(credentials, descriptors);
    }

    static boolean isLockedRoot(User user) {
        if (!isRoot(user.id())) {
            return false;
        }
        for (Credential credential : user.credentials()) {
            if (credential.keyId() != null
                    && credential.keyId().startsWith(ROOT_LOCKED_GENERATION_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    static boolean publicKeysAreEqual(String expected, byte[] provided) {
        if (expected == null || provided == null) {
            return false;
        }
        try {
            PublicKey userKey = KeyUtils.readPublicKeyFromString(expected);
            return Arrays.equals(userKey.getEncoded(), provided);
        } catch (IllegalArgumentException e) {
            log.warn("Cannot parse public key credential.", e);
            return false;
        }
    }

    static List<User> findUsers(AccessControl acl, String userId) {
        if (acl == null) {
            return List.of();
        }
        List<User> matches = new ArrayList<>();
        for (User user : acl.users()) {
            if (user.id() != null && user.id().equalsIgnoreCase(userId)) {
                matches.add(user);
            }
        }
        return matches;
    }

    record ParsedSshCredential(PublicKey publicKey, SshCredential descriptor) {
    }

    record ParsedSshCredentials(
            Map<String, ParsedSshCredential> byEncodedKey,
            List<SshCredential> descriptors) {
        ParsedSshCredentials {
            byEncodedKey = Map.copyOf(byEncodedKey);
            descriptors = List.copyOf(descriptors);
        }
    }

    static final class InvalidStoredSshKeyException extends RuntimeException {
        private InvalidStoredSshKeyException(Throwable cause) {
            super(cause);
        }
    }

}
