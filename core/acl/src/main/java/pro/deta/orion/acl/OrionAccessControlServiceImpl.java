package pro.deta.orion.acl;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.PlainRootTokenAccess;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.SshKeyEnrollmentResult;
import pro.deta.orion.auth.SshCredential;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.SshCredentialUpdateResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.event.type.RequestToAclUpdate;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static pro.deta.orion.schema.acl.AccessControl.CredentialType.OPENSSH_PUBLIC_KEY;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.ARGON2;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;
import static pro.deta.orion.util.Result.Failure.generalFailure;

@Slf4j
@Singleton
public class OrionAccessControlServiceImpl implements OrionAccessControlService, ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
    private static final String ROOT_USER_ID = "root";
    private static final String ROOT_AUTH_GENERATION_PREFIX = "root-auth-generation:";
    private static final String ROOT_LOCKED_GENERATION_PREFIX = "root-auth-locked:";

    private final XmlService xmlService = new XmlService();
    private final AccessControlStorage accessControlStorage;
    private final OrionPasswordHashingService orionPasswordHashingService;
    private final OrionProvider orionProvider;
    private final OrionRuntimeOptions runtimeOptions;
    private final ServerIdentityCapability serverIdentity;
    private final OrionDesiredState desiredState;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final AtomicReference<AccessControl> accessControl = new AtomicReference<>();
    private final AtomicReference<char[]> plainRootToken = new AtomicReference<>();
    private final Object reloadLock = new Object();
    private volatile AccessControlStorage.ChangeSubscription changeSubscription;

    @Inject
    public OrionAccessControlServiceImpl(
            AccessControlStorage accessControlStorage,
            OrionPasswordHashingService orionPasswordHashingService,
            OrionProvider orionProvider,
            OrionRuntimeOptions runtimeOptions,
            ServerIdentityCapability serverIdentity,
            OrionDesiredState desiredState) {
        this.accessControlStorage = accessControlStorage;
        this.orionPasswordHashingService = orionPasswordHashingService;
        this.orionProvider = orionProvider;
        this.runtimeOptions = runtimeOptions;
        this.serverIdentity = serverIdentity;
        this.desiredState = desiredState;
        this.jwtAccessTokenService = new JwtAccessTokenService(serverIdentity);
    }

    private void loadAccessControlOnStart() {
        orionProvider.getEventManager().registerTypeHandler(RequestToAclUpdate.class, (event) -> {
            log.debug("Request to update ACL received: {}", event);
            requestToUpdate();
        });
        changeSubscription = accessControlStorage.onChange(initiator -> requestToUpdate());
        try {
            switch (loadValidatedAccessControlSnapshot()) {
                case Result.Success<AccessControlSnapshot>(var snapshot) -> {
                    if (runtimeOptions.resetRootPassword()) {
                        resetRootPassword(snapshot);
                    } else {
                        requestAclUpdateAndWait("access-control start");
                    }
                }
                case Result.Failure<AccessControlSnapshot> f -> {
                    if (f.code() == Result.FailureCode.NOT_FOUND) {
                        if (!accessControlStorage.createIfMissing()) {
                            throw new IllegalStateException("ACL not found and default ACL creation is disabled.");
                        }
                        if (runtimeOptions.resetRootPassword()) {
                            resetRootPassword(AccessControlSnapshot.singleFile(
                                    accessControlStorage.primaryPath(),
                                    serializeAccessControlConfiguration(new AccessControlDraft().toAccessControl())));
                        } else {
                            createDefaultAccessControlAndRequestUpdate();
                        }
                    } else {
                        log.error("Error while preparing configuration repository.", f.throwable());
                        throw new IllegalStateException("Configuration repository not initialized.", f.throwable());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error while preparing configuration repository.", e);
            throw new IllegalStateException("Configuration repository not initialized.", e);
        }
    }

    @Override
    public void onStart() {
        loadAccessControlOnStart();
    }

    @Override
    public void onStop() {
        AccessControlStorage.ChangeSubscription subscription = changeSubscription;
        if (subscription != null) {
            subscription.close();
            changeSubscription = null;
        }
        // ACL state remains available until process shutdown.
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return accessControl.get() != null;
    }

    private void printAndClearPlainTextPasswordMessage(PrintStream out, char[] secureChars) {
        out.println();
        out.print("---ROOT PASSWORD: ");
        plainRootToken.set(secureChars.clone());
        for (int i = 0; i < secureChars.length; i++) {
            out.print(secureChars[i]);
            secureChars[i] = 0;
        }
        out.println();
    }

    public char[] plainRootToken(PlainRootTokenAccess access) {
        if (access == null) {
            throw new SecurityException("Plain root token access is required");
        }
        char[] token = plainRootToken.get();
        if (token == null) {
            throw new IllegalStateException(
                    "Plain root token is available only after root password generation");
        }
        return token.clone();
    }

    private void updateAccessControl(AccessControl accessControl) {
        this.accessControl.set(accessControl);
    }


    @Override
    public void addKeyToUser(String username, String publicKey) {
        addSshKeysToUser(username, List.of(publicKey));
    }

    @Override
    public void addSshKeysToUser(String username, List<String> publicKeys) {
        switch (addSshCredentials(username, publicKeys)) {
            case SshCredentialUpdateResult.Success ignored -> {
            }
            case SshCredentialUpdateResult.Failure failure -> {
                if (failure.code() == SshCredentialFailureCode.INVALID_KEY) {
                    throw new IllegalArgumentException("Invalid SSH public key", failure.throwable());
                }
                throw new IllegalStateException(
                        "SSH key enrollment failed: " + failure.reason(),
                        failure.throwable());
            }
        }
    }

    @Override
    public SshCredentialListResult listSshCredentials(String userId) {
        if (userId == null || userId.isBlank()) {
            return SshCredentialListResult.failure(
                    SshCredentialFailureCode.USER_NOT_FOUND,
                    "User is not available");
        }
        synchronized (reloadLock) {
            return switch (accessControlStorage.load()) {
                case Result.Failure<AccessControlSnapshot> failure -> SshCredentialListResult.failure(
                        SshCredentialFailureCode.PERSISTENCE_FAILED,
                        "Cannot load SSH credentials",
                        failure.throwable());
                case Result.Success<AccessControlSnapshot>(var snapshot) -> listSshCredentials(snapshot, userId);
            };
        }
    }

    private SshCredentialListResult listSshCredentials(AccessControlSnapshot snapshot, String userId) {
        try {
            UserLocation location = findUserLocation(accessControlDrafts(snapshot), userId);
            if (location == null) {
                return SshCredentialListResult.failure(
                        SshCredentialFailureCode.USER_NOT_FOUND,
                        "User is not available");
            }
            return SshCredentialListResult.success(sshCredentials(location.user()).descriptors());
        } catch (InvalidStoredSshKeyException e) {
            return SshCredentialListResult.failure(
                    SshCredentialFailureCode.INVALID_STORED_KEY,
                    "Stored SSH credential is invalid",
                    e);
        } catch (RuntimeException e) {
            return SshCredentialListResult.failure(
                    SshCredentialFailureCode.PERSISTENCE_FAILED,
                    "Cannot load SSH credentials",
                    e);
        }
    }

    @Override
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

        synchronized (reloadLock) {
            return switch (accessControlStorage.load()) {
                case Result.Failure<AccessControlSnapshot> failure -> SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.PERSISTENCE_FAILED,
                        "Cannot load SSH credentials",
                        List.of(),
                        failure.throwable());
                case Result.Success<AccessControlSnapshot>(var snapshot) -> addSshCredentials(
                        snapshot,
                        userId,
                        parsedKeys);
            };
        }
    }

    private SshCredentialUpdateResult addSshCredentials(
            AccessControlSnapshot snapshot,
            String userId,
            List<PublicKey> publicKeys) {
        try {
            Map<String, AccessControlDraft> drafts = accessControlDrafts(snapshot);
            UserLocation location = findUserLocation(drafts, userId);
            if (location == null) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.USER_NOT_FOUND,
                        "User is not available");
            }
            if (isLockedRoot(location.user())) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.ROOT_LOCKED,
                        "Root SSH credentials are locked");
            }
            ParsedSshCredentials existing = sshCredentials(location.user());
            boolean changed = addMissingPublicKeys(location.user(), existing, publicKeys);
            if (!changed) {
                return SshCredentialUpdateResult.success(existing.descriptors(), false);
            }
            saveCredentialDraft(snapshot, location, "add SSH credentials");
            return SshCredentialUpdateResult.success(sshCredentials(location.user()).descriptors(), true);
        } catch (InvalidStoredSshKeyException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.INVALID_STORED_KEY,
                    "Stored SSH credential is invalid",
                    List.of(),
                    e);
        } catch (AccessControlConcurrentUpdateException e) {
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

    @Override
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
        synchronized (reloadLock) {
            return switch (accessControlStorage.load()) {
                case Result.Failure<AccessControlSnapshot> failure -> SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.PERSISTENCE_FAILED,
                        "Cannot load SSH credentials",
                        List.of(),
                        failure.throwable());
                case Result.Success<AccessControlSnapshot>(var snapshot) -> removeSshCredential(
                        snapshot,
                        userId,
                        prefix,
                        force);
            };
        }
    }

    private SshCredentialUpdateResult removeSshCredential(
            AccessControlSnapshot snapshot,
            String userId,
            String fingerprintPrefix,
            boolean force) {
        try {
            UserLocation location = findUserLocation(accessControlDrafts(snapshot), userId);
            if (location == null) {
                return SshCredentialUpdateResult.failure(
                        SshCredentialFailureCode.USER_NOT_FOUND,
                        "User is not available");
            }
            ParsedSshCredentials existing = sshCredentials(location.user());
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

            removePublicKey(location.user(), matches.getFirst().publicKey());
            if (existing.byEncodedKey().size() == 1 && isRoot(location.user().getId())) {
                lockRoot(location.user());
            }
            saveCredentialDraft(snapshot, location, "remove SSH credential");
            return SshCredentialUpdateResult.success(sshCredentials(location.user()).descriptors(), true);
        } catch (InvalidStoredSshKeyException e) {
            return SshCredentialUpdateResult.failure(
                    SshCredentialFailureCode.INVALID_STORED_KEY,
                    "Stored SSH credential is invalid",
                    List.of(),
                    e);
        } catch (AccessControlConcurrentUpdateException e) {
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

    @Override
    public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
        new AccessControlWriter().createOrUpdateUser(userUpdate);
    }

    @Override
    public boolean userExists(String userName) {
        return findSingleUser(userName) instanceof Result.Success<?>;
    }

    @Override
    public AuthenticationResult authenticateUser(String userName, byte[] encodedData) {
        Result<AccessControl.User> user = findSingleUser(userName);
        if (user instanceof Result.Success<AccessControl.User>(var u)) {
            if (isLockedRoot(u)) {
                return AuthenticationResult.failure("authentication failed");
            }
            if (rootRecoveryGeneration(u) != null) {
                log.warn("Attempt to use the root recovery password outside SSH key enrollment.");
                return AuthenticationResult.failure("authentication failed");
            }
            if (performAuthentication(u, encodedData))
                return createUserIdentity(u);
        }
        log.warn("Attempt to authenticate as '{}' failed.", userName);
        return AuthenticationResult.failure("authentication failed");
    }

    @Override
    public SshKeyEnrollmentAuthentication authenticateSshKeyEnrollment(
            String userName,
            byte[] credential) {
        Result<AccessControl.User> user = findSingleUser(userName);
        if (user instanceof Result.Success<AccessControl.User>(var matchedUser)
                && !isLockedRoot(matchedUser)
                && performPasswordAuthentication(matchedUser, credential)) {
            String recoveryGeneration = rootRecoveryGeneration(matchedUser);
            if (isGenerationAwareRoot(matchedUser) && recoveryGeneration == null) {
                return SshKeyEnrollmentAuthentication.failure("authentication failed");
            }
            return switch (createUserIdentity(matchedUser)) {
                case AuthenticationResult.Success(var identity) -> SshKeyEnrollmentAuthentication.success(
                        identity,
                        recoveryGeneration);
                case AuthenticationResult.Failure(var reason, var throwable) ->
                        SshKeyEnrollmentAuthentication.failure(reason, throwable);
            };
        }
        log.warn("SSH key enrollment authentication as '{}' failed.", userName);
        return SshKeyEnrollmentAuthentication.failure("authentication failed");
    }

    @Override
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

        synchronized (reloadLock) {
            return switch (accessControlStorage.load()) {
                case Result.Failure<AccessControlSnapshot> failure ->
                        SshKeyEnrollmentResult.failure("key enrollment failed", failure.throwable());
                case Result.Success<AccessControlSnapshot>(var snapshot) -> completeRootSshKeyEnrollment(
                        snapshot,
                        expectedGeneration,
                        parsedKeys);
            };
        }
    }

    private SshKeyEnrollmentResult completeRootSshKeyEnrollment(
            AccessControlSnapshot snapshot,
            String expectedGeneration,
            List<PublicKey> publicKeys) {
        try {
            Map<String, AccessControlDraft> drafts = accessControlDrafts(snapshot);
            List<RootLocation> roots = rootLocations(drafts);
            if (roots.size() != 1) {
                return SshKeyEnrollmentResult.failure("key enrollment failed");
            }
            RootLocation location = roots.getFirst();
            AccessControl.User currentRoot = location.user().toAccessControl();
            if (!expectedGeneration.equals(rootRecoveryGeneration(currentRoot))) {
                return SshKeyEnrollmentResult.failure("key enrollment failed");
            }

            location.user().getCredentials().clear();
            for (PublicKey publicKey : publicKeys) {
                location.user().addCredential(
                        OPENSSH_PUBLIC_KEY,
                        generationKeyId(expectedGeneration),
                        PublicKeyEntry.toString(publicKey));
            }
            Map<String, byte[]> updatedFiles = new LinkedHashMap<>(snapshot.files());
            updatedFiles.put(
                    location.path(),
                    serializeAccessControlConfiguration(
                            snapshot,
                            location.path(),
                            location.draft().toAccessControl()));
            saveAccessControlSnapshotAndReload(
                    new AccessControlSnapshot(updatedFiles, snapshot.version()),
                    "complete root SSH key enrollment",
                    new UserEmail(ROOT_USER_ID, Objects.requireNonNullElse(location.user().getEmail(), "root@orion.pro")));
            return SshKeyEnrollmentResult.success();
        } catch (RuntimeException e) {
            return SshKeyEnrollmentResult.failure("key enrollment failed", e);
        }
    }

    @Override
    public AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey) {
        Result<AccessControl.User> user = findSingleUser(userName);
        if (user instanceof Result.Success<AccessControl.User>(var matchedUser)
                && !isLockedRoot(matchedUser)
                && performPublicKeyAuthentication(matchedUser, encodedPublicKey)) {
            return createUserIdentity(matchedUser);
        }
        log.warn("SSH public-key authentication as '{}' failed.", userName);
        return AuthenticationResult.failure("authentication failed");
    }

    @Override
    public AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey) {
        List<AccessControl.User> matchingUsers = new ArrayList<>();
        AccessControl currentAccessControl = accessControl.get();
        if (currentAccessControl != null) {
            for (AccessControl.User user : currentAccessControl.getUsers()) {
                if (!isLockedRoot(user) && performPublicKeyAuthentication(user, encodedPublicKey)) {
                    matchingUsers.add(user);
                }
            }
        }
        if (matchingUsers.size() == 1) {
            return createUserIdentity(matchingUsers.getFirst());
        }
        log.warn("Git SSH public key resolved to {} users.", matchingUsers.size());
        return AuthenticationResult.failure("authentication failed");
    }

    @Override
    public AuthenticationResult authenticateToken(byte[] token) {
        String tokenValue = new String(token, StandardCharsets.UTF_8);
        return switch (jwtAccessTokenService.verify(tokenValue)) {
            case JwtAccessTokenService.VerificationResult.Failure(var reason) ->
                    AuthenticationResult.failure(reason);
            case JwtAccessTokenService.VerificationResult.Success(var subject, var authenticationGeneration) -> {
                Result<AccessControl.User> user = findSingleUser(subject);
                if (user instanceof Result.Success<AccessControl.User>(var u)) {
                    if (isLockedRoot(u)) {
                        yield AuthenticationResult.failure("authentication failed");
                    }
                    String currentGeneration = rootAuthenticationGeneration(u);
                    if (isGenerationAwareRoot(u)
                            && (currentGeneration == null || !currentGeneration.equals(authenticationGeneration))) {
                        yield AuthenticationResult.failure("authentication failed");
                    }
                    yield createUserIdentity(u);
                }
                yield AuthenticationResult.failure("authentication failed");
            }
        };
    }

    @Override
    public TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds) {
        return switch (authenticateUser(userName, credential)) {
            case AuthenticationResult.Failure(var reason, var throwable) ->
                    TokenIssueResult.failure(reason, throwable);
            case AuthenticationResult.Success(var userIdentity) -> issueTokenFor(userIdentity, expiresInSeconds);
        };
    }

    @Override
    public TokenIssueResult issueTokenFor(UserIdentity userIdentity, long expiresInSeconds) {
        if (userIdentity == null
                || userIdentity.isAnonymous()
                || userIdentity.getUserId() == null
                || userIdentity.getUserId().isBlank()) {
            return TokenIssueResult.failure("authenticated user is required");
        }
        Result<AccessControl.User> user = findSingleUser(userIdentity.getUserId());
        if (user instanceof Result.Failure<AccessControl.User>(var code, var message, var throwable)) {
            return TokenIssueResult.failure("user is not available for token issue", throwable);
        }
        if (!(user instanceof Result.Success<AccessControl.User>(var currentUser))) {
            return TokenIssueResult.failure("user is not available for token issue");
        }
        if (isLockedRoot(currentUser)) {
            return TokenIssueResult.failure("root authentication is locked");
        }
        String authenticationGeneration = rootAuthenticationGeneration(currentUser);
        if (isGenerationAwareRoot(currentUser) && authenticationGeneration == null) {
            return TokenIssueResult.failure("root authentication state is invalid");
        }
        if (rootRecoveryGeneration(currentUser) != null) {
            return TokenIssueResult.failure("root key enrollment is required");
        }
        try {
            JwtAccessTokenService.IssuedToken token = jwtAccessTokenService.issue(
                    userIdentity.getUserId(),
                    expiresInSeconds,
                    authenticationGeneration);
            return TokenIssueResult.success(token.value(), token.expiresAtEpochSecond());
        } catch (GeneralSecurityException | RuntimeException e) {
            return TokenIssueResult.failure("token issue failed", e);
        }
    }

    @Override
    public byte[] accessControlConfigurationFile() {
        return switch (accessControlStorage.load()) {
            case Result.Success<AccessControlSnapshot>(var snapshot) -> {
                byte[] content = snapshot.files().get(accessControlStorage.primaryPath());
                if (content == null) {
                    throw new IllegalStateException(
                            "Primary ACL configuration file is missing: "
                                    + accessControlStorage.primaryPath());
                }
                yield serializeOrionConfiguration(parseOrionConfiguration(
                        content,
                        accessControlStorage.primaryPath()));
            }
            case Result.Failure<AccessControlSnapshot> failure ->
                    throw new IllegalStateException("Cannot load ACL configuration file", failure.throwable());
        };
    }

    @Override
    public void saveAccessControlConfigurationFile(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("ACL configuration content is required");
        }
        String primaryPath = accessControlStorage.primaryPath();
        OrionDocument document = parseOrionConfiguration(content, primaryPath);
        AccessControlSnapshot snapshot = AccessControlSnapshot.singleFile(
                primaryPath,
                serializeOrionConfiguration(document));
        switch (documentFrom(snapshot)) {
            case Result.Success<OrionDocument> ignored -> {
                accessControlStorage.save(
                        snapshot,
                        new AccessControlSaveRequest(
                                "saveAccessControlConfigurationFile() " + primaryPath,
                                UserEmail.EMPTY));
                requestAclUpdateAndWait("saveAccessControlConfigurationFile()");
            }
            case Result.Failure<OrionDocument> failure ->
                    throw new IllegalArgumentException(
                            "Invalid ACL configuration file: " + failure.message(),
                            failure.throwable());
        }
    }

    private AccessControl parseAccessControlConfiguration(byte[] content, String sourceName) {
        return parseOrionConfiguration(content, sourceName).system().accessControl();
    }

    private OrionDocument parseOrionConfiguration(byte[] content, String sourceName) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return xmlService.deserializeDocument(input);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Invalid ACL configuration file: Cannot parse ACL file " + sourceName,
                    e);
        }
    }

    private byte[] serializeAccessControlConfiguration(AccessControl accessControl) {
        return serializeOrionConfiguration(OrionDocument.withAccessControl(accessControl));
    }

    private byte[] serializeAccessControlConfiguration(
            AccessControlSnapshot snapshot,
            String path,
            AccessControl accessControl) {
        byte[] content = snapshot.files().get(path);
        OrionDocument document = content == null
                ? OrionDocument.withAccessControl(accessControl)
                : parseOrionConfiguration(content, path).replaceAccessControl(accessControl);
        return serializeOrionConfiguration(document);
    }

    private byte[] serializeOrionConfiguration(OrionDocument document) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            xmlService.serializeDocument(document, output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize ACL configuration file", e);
        }
    }

    private void requestToUpdate() {
        synchronized (reloadLock) {
            switch (loadValidatedAccessControlSnapshot()) {
                case Result.Success<AccessControlSnapshot>(var snapshot) ->
                        prepareAndUpdateAccessControl(snapshot);
                case Result.Failure<AccessControlSnapshot> f ->
                        log.error("Retaining the last valid ACL after reload failure: [{}] {}",
                                f.code(), f.message(), f.throwable());
            }
        }
    }

    private void requestAclUpdateAndWait(String initiator) {
        orionProvider.getEventManager().publishAndWait(new RequestToAclUpdate(initiator));
    }

    private AccessControl createDefaultAccessControl(
            String passwordHash,
            AccessControl.CredentialType passwordCredentialType) {
        AccessControlDraft draft = ACLUtil.generateDefaultAccessControl(passwordHash, passwordCredentialType).toDraft();
        synchronizeInternalServerKeysToRoot(draft);
        return draft.toAccessControl();
    }

    private void resetRootPassword(AccessControlSnapshot snapshot) {
        char[] rootPassword = orionPasswordHashingService.generateRandomString(10);
        try {
            String passwordHash = orionPasswordHashingService.calculateHash(ARGON2, rootPassword);
            String authenticationGeneration = UUID.randomUUID().toString();
            Map<String, AccessControlDraft> drafts = accessControlDrafts(snapshot);
            Map<String, byte[]> updatedFiles = new LinkedHashMap<>(snapshot.files());
            AccessControl canonical = ACLUtil.generateDefaultAccessControl(
                    passwordHash,
                    AccessControl.CredentialType.ARGON2);
            AccessControlDraft.User root = AccessControlDraft.User.from(canonical.getUsers().getFirst());
            root.getCredentials().getFirst().setKeyId(generationKeyId(authenticationGeneration));
            removeRootAndCanonicalAuthorization(snapshot, drafts, canonical, updatedFiles);
            AccessControlDraft primary = primaryDraft(drafts);
            addCanonicalRootAuthorization(primary, canonical);
            primary.getUsers().add(root);
            updatedFiles.put(
                    accessControlStorage.primaryPath(),
                    serializeAccessControlConfiguration(
                            snapshot,
                            accessControlStorage.primaryPath(),
                            primary.toAccessControl()));
            saveAccessControlSnapshotAndReload(
                    new AccessControlSnapshot(updatedFiles, snapshot.version()),
                    "root password reset",
                    new UserEmail(ROOT_USER_ID, Objects.requireNonNullElse(root.getEmail(), "root@orion.pro")));
            printAndClearPlainTextPasswordMessage(System.out, rootPassword);
        } finally {
            Arrays.fill(rootPassword, '\0');
        }
    }

    private Map<String, AccessControlDraft> accessControlDrafts(AccessControlSnapshot snapshot) {
        Map<String, AccessControlDraft> drafts = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
            drafts.put(
                    entry.getKey(),
                    parseAccessControlConfiguration(entry.getValue(), entry.getKey()).toDraft());
        }
        return drafts;
    }

    private List<RootLocation> rootLocations(Map<String, AccessControlDraft> drafts) {
        List<RootLocation> roots = new ArrayList<>();
        for (Map.Entry<String, AccessControlDraft> entry : drafts.entrySet()) {
            for (AccessControlDraft.User user : entry.getValue().getUsers()) {
                if (isRoot(user.getId())) {
                    roots.add(new RootLocation(entry.getKey(), entry.getValue(), user));
                }
            }
        }
        return List.copyOf(roots);
    }

    private AccessControlDraft primaryDraft(Map<String, AccessControlDraft> drafts) {
        AccessControlDraft primary = drafts.get(accessControlStorage.primaryPath());
        if (primary == null) {
            throw new IllegalStateException("Primary ACL configuration file is missing: "
                    + accessControlStorage.primaryPath());
        }
        return primary;
    }

    private void removeRootAndCanonicalAuthorization(
            AccessControlSnapshot snapshot,
            Map<String, AccessControlDraft> drafts,
            AccessControl canonical,
            Map<String, byte[]> updatedFiles) {
        for (Map.Entry<String, AccessControlDraft> entry : drafts.entrySet()) {
            AccessControlDraft draft = entry.getValue();
            boolean changed = draft.getUsers().removeIf(user ->
                    user.getId() != null && ROOT_USER_ID.equalsIgnoreCase(user.getId()));
            for (AccessControl.Role canonicalRole : canonical.getRoles()) {
                changed |= draft.getRoles().removeIf(role -> idsAreEqual(role.getId(), canonicalRole.getId()));
            }
            for (AccessControl.Grant canonicalGrant : canonical.getGrants()) {
                changed |= draft.getGrants().removeIf(
                        grant -> idsAreEqual(grant.getId(), canonicalGrant.getId()));
            }
            if (changed) {
                updatedFiles.put(
                        entry.getKey(),
                        serializeAccessControlConfiguration(
                                snapshot,
                                entry.getKey(),
                                draft.toAccessControl()));
            }
        }
    }

    private void addCanonicalRootAuthorization(AccessControlDraft draft, AccessControl canonical) {
        for (AccessControl.Role canonicalRole : canonical.getRoles()) {
            draft.getRoles().add(AccessControlDraft.Role.from(canonicalRole));
        }
        for (AccessControl.Grant canonicalGrant : canonical.getGrants()) {
            draft.getGrants().add(AccessControlDraft.Grant.from(canonicalGrant));
        }
    }

    private boolean idsAreEqual(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private void createDefaultAccessControlAndRequestUpdate() {
        PasswordHashingAlgorithm passwordHashingAlgorithm = defaultPasswordHashingAlgorithm();
        char[] defaultRootPassword = orionPasswordHashingService.generateRandomString(10);
        try {
            String passwordHash = orionPasswordHashingService.calculateHash(
                    passwordHashingAlgorithm,
                    defaultRootPassword);
            AccessControl ac = createDefaultAccessControl(
                    passwordHash,
                    defaultPasswordCredentialType(passwordHashingAlgorithm));
            saveAccessControlAndReload(ac, "default scheme applied", UserEmail.EMPTY);
            printAndClearPlainTextPasswordMessage(System.out, defaultRootPassword);
        } finally {
            Arrays.fill(defaultRootPassword, '\0');
        }
    }

    protected PasswordHashingAlgorithm defaultPasswordHashingAlgorithm() {
        return ARGON2;
    }

    private AccessControl.CredentialType defaultPasswordCredentialType(PasswordHashingAlgorithm algorithm) {
        return switch (algorithm) {
            case ARGON2 -> AccessControl.CredentialType.ARGON2;
            case SHA1 -> AccessControl.CredentialType.SHA1;
        };
    }

    private void prepareAndUpdateAccessControl(AccessControlSnapshot loadedSnapshot) {
        AccessControlSnapshot preparedSnapshot = loadedSnapshot;
        Map<String, AccessControlDraft> drafts = accessControlDrafts(loadedSnapshot);
        List<RootLocation> roots = rootLocations(drafts);
        if (roots.size() == 1 && synchronizeInternalServerKeysToRoot(roots.getFirst().user())) {
            RootLocation root = roots.getFirst();
            Map<String, byte[]> updatedFiles = new LinkedHashMap<>(loadedSnapshot.files());
            updatedFiles.put(
                    root.path(),
                    serializeAccessControlConfiguration(
                            loadedSnapshot,
                            root.path(),
                            root.draft().toAccessControl()));
            accessControlStorage.save(
                    new AccessControlSnapshot(updatedFiles, loadedSnapshot.version()),
                    new AccessControlSaveRequest("add internal server keys to root", UserEmail.EMPTY));
            preparedSnapshot = switch (loadValidatedAccessControlSnapshot()) {
                case Result.Success<AccessControlSnapshot>(var snapshot) -> snapshot;
                case Result.Failure<AccessControlSnapshot> failure -> throw new IllegalStateException(
                        "Cannot reload ACL after internal server-key synchronization: [" + failure.code() + "] "
                                + failure.message(),
                        failure.throwable());
            };
        }
        OrionDocument document = documentFrom(preparedSnapshot).valueOrFailure("prepared desired state");
        desiredState.publish(document, preparedSnapshot.version());
        updateAccessControl(document.system().accessControl());
    }

    private boolean synchronizeInternalServerKeysToRoot(AccessControlDraft draft) {
        AccessControlDraft.User rootUser = findRootUser(draft);
        return rootUser != null && synchronizeInternalServerKeysToRoot(rootUser);
    }

    private boolean synchronizeInternalServerKeysToRoot(AccessControlDraft.User rootUser) {
        if (rootUser == null || isGenerationAwareRoot(rootUser)) {
            return false;
        }

        boolean changed = removeRetainedServerKeys(rootUser);
        for (PublicKey publicKey : serverPublicKeys()) {
            if (!hasPublicKeyCredential(rootUser, publicKey)) {
                rootUser.addCredential(OPENSSH_PUBLIC_KEY, KeyUtils.publicKeyToString(publicKey));
                changed = true;
            }
        }
        return changed;
    }

    private boolean removeRetainedServerKeys(AccessControlDraft.User rootUser) {
        Set<String> retained = new HashSet<>();
        for (PublicKey publicKey : retainedServerPublicKeys()) {
            retained.add(KeyUtils.publicKeyToString(publicKey));
        }
        if (retained.isEmpty()) {
            return false;
        }
        return rootUser.getCredentials().removeIf(credential ->
                credential.getType() == OPENSSH_PUBLIC_KEY
                        && retained.contains(credential.getValue()));
    }

    private List<PublicKey> serverPublicKeys() {
        try {
            return serverIdentity.publicKeys();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load server identity public keys", e);
        }
    }

    private List<PublicKey> retainedServerPublicKeys() {
        try {
            return serverIdentity.retainedPublicKeys();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load retained server identity public keys", e);
        }
    }

    private static String generationKeyId(String generation) {
        return ROOT_AUTH_GENERATION_PREFIX + generation;
    }

    private static String generationFromKeyId(String keyId) {
        if (keyId == null || !keyId.startsWith(ROOT_AUTH_GENERATION_PREFIX)) {
            return null;
        }
        String generation = keyId.substring(ROOT_AUTH_GENERATION_PREFIX.length());
        return generation.isBlank() ? null : generation;
    }

    private static String rootRecoveryGeneration(AccessControl.User user) {
        if (!isRoot(user.getId()) || user.getCredentials().size() != 1) {
            return null;
        }
        AccessControl.Credential credential = user.getCredentials().getFirst();
        return credential.getType() == AccessControl.CredentialType.ARGON2
                ? generationFromKeyId(credential.getKeyId())
                : null;
    }

    private static String rootAuthenticationGeneration(AccessControl.User user) {
        if (!isRoot(user.getId()) || user.getCredentials().isEmpty()) {
            return null;
        }
        AccessControl.CredentialType expectedType = user.getCredentials().size() == 1
                && user.getCredentials().getFirst().getType() == AccessControl.CredentialType.ARGON2
                ? AccessControl.CredentialType.ARGON2
                : OPENSSH_PUBLIC_KEY;
        String generation = null;
        for (AccessControl.Credential credential : user.getCredentials()) {
            if (credential.getType() != expectedType) {
                return null;
            }
            String candidate = generationFromKeyId(credential.getKeyId());
            if (candidate == null || generation != null && !generation.equals(candidate)) {
                return null;
            }
            generation = candidate;
        }
        return generation;
    }

    private static String rootAuthenticationGeneration(AccessControlDraft.User user) {
        if (!isRoot(user.getId()) || user.getCredentials().isEmpty()) {
            return null;
        }
        AccessControl.CredentialType expectedType = user.getCredentials().size() == 1
                && user.getCredentials().getFirst().getType() == AccessControl.CredentialType.ARGON2
                ? AccessControl.CredentialType.ARGON2
                : OPENSSH_PUBLIC_KEY;
        String generation = null;
        for (AccessControlDraft.Credential credential : user.getCredentials()) {
            if (credential.getType() != expectedType) {
                return null;
            }
            String candidate = generationFromKeyId(credential.getKeyId());
            if (candidate == null || generation != null && !generation.equals(candidate)) {
                return null;
            }
            generation = candidate;
        }
        return generation;
    }

    private static boolean isGenerationAwareRoot(AccessControl.User user) {
        if (!isRoot(user.getId())) {
            return false;
        }
        for (AccessControl.Credential credential : user.getCredentials()) {
            if (credential.getKeyId() != null
                    && (credential.getKeyId().startsWith(ROOT_AUTH_GENERATION_PREFIX)
                    || credential.getKeyId().startsWith(ROOT_LOCKED_GENERATION_PREFIX))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGenerationAwareRoot(AccessControlDraft.User user) {
        if (!isRoot(user.getId())) {
            return false;
        }
        for (AccessControlDraft.Credential credential : user.getCredentials()) {
            if (credential.getKeyId() != null
                    && (credential.getKeyId().startsWith(ROOT_AUTH_GENERATION_PREFIX)
                    || credential.getKeyId().startsWith(ROOT_LOCKED_GENERATION_PREFIX))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRoot(String userId) {
        return userId != null && ROOT_USER_ID.equalsIgnoreCase(userId);
    }

    private AccessControlDraft.User findRootUser(AccessControlDraft draft) {
        for (AccessControlDraft.User user : draft.getUsers()) {
            if (user.getId() != null && ROOT_USER_ID.equalsIgnoreCase(user.getId())) {
                return user;
            }
        }
        return null;
    }

    private boolean hasPublicKeyCredential(AccessControlDraft.User user, PublicKey publicKey) {
        for (AccessControlDraft.Credential credential : user.getCredentials()) {
            if (credential.getType() == OPENSSH_PUBLIC_KEY
                    && publicKeysAreEqual(credential.getValue(), publicKey.getEncoded())) {
                return true;
            }
        }
        return false;
    }

    private UserLocation findUserLocation(Map<String, AccessControlDraft> drafts, String userId) {
        UserLocation matched = null;
        for (Map.Entry<String, AccessControlDraft> entry : drafts.entrySet()) {
            for (AccessControlDraft.User user : entry.getValue().getUsers()) {
                if (user.getId() != null && user.getId().equalsIgnoreCase(userId)) {
                    if (matched != null) {
                        throw new IllegalStateException("More than one user matches the requested id");
                    }
                    matched = new UserLocation(entry.getKey(), entry.getValue(), user);
                }
            }
        }
        return matched;
    }

    private ParsedSshCredentials sshCredentials(AccessControlDraft.User user) {
        Map<String, ParsedSshCredential> credentials = new LinkedHashMap<>();
        for (AccessControlDraft.Credential credential : user.getCredentials()) {
            if (credential.getType() != OPENSSH_PUBLIC_KEY) {
                continue;
            }
            PublicKey publicKey;
            try {
                publicKey = KeyUtils.readPublicKeyFromString(credential.getValue());
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

    private boolean addMissingPublicKeys(
            AccessControlDraft.User user,
            ParsedSshCredentials existing,
            List<PublicKey> publicKeys) {
        boolean changed = false;
        String generation = rootAuthenticationGeneration(user);
        Set<String> known = new HashSet<>(existing.byEncodedKey().keySet());
        for (PublicKey publicKey : publicKeys) {
            String encoded = Base64.getEncoder().encodeToString(publicKey.getEncoded());
            if (!known.add(encoded)) {
                continue;
            }
            String canonical = PublicKeyEntry.toString(publicKey);
            if (generation == null) {
                user.addCredential(OPENSSH_PUBLIC_KEY, canonical);
            } else {
                user.addCredential(OPENSSH_PUBLIC_KEY, generationKeyId(generation), canonical);
            }
            changed = true;
        }
        return changed;
    }

    private void removePublicKey(AccessControlDraft.User user, PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        user.getCredentials().removeIf(credential -> credential.getType() == OPENSSH_PUBLIC_KEY
                && publicKeysAreEqual(credential.getValue(), encoded));
    }

    private void lockRoot(AccessControlDraft.User root) {
        char[] markerSecret = orionPasswordHashingService.generateRandomString(32);
        try {
            String markerHash = orionPasswordHashingService.calculateHash(ARGON2, markerSecret);
            root.addCredential(
                    AccessControl.CredentialType.ARGON2,
                    ROOT_LOCKED_GENERATION_PREFIX + UUID.randomUUID(),
                    markerHash);
        } finally {
            Arrays.fill(markerSecret, '\0');
        }
    }

    private void saveCredentialDraft(
            AccessControlSnapshot snapshot,
            UserLocation location,
            String operation) {
        Map<String, byte[]> files = new LinkedHashMap<>(snapshot.files());
        files.put(
                location.path(),
                serializeAccessControlConfiguration(
                        snapshot,
                        location.path(),
                        location.draft().toAccessControl()));
        saveAccessControlSnapshotAndReload(
                new AccessControlSnapshot(files, snapshot.version()),
                operation + " for " + location.user().getId(),
                new UserEmail(
                        location.user().getId(),
                        Objects.requireNonNullElse(location.user().getEmail(), "")));
    }

    private static boolean isLockedRoot(AccessControlDraft.User user) {
        if (!isRoot(user.getId())) {
            return false;
        }
        for (AccessControlDraft.Credential credential : user.getCredentials()) {
            if (credential.getKeyId() != null
                    && credential.getKeyId().startsWith(ROOT_LOCKED_GENERATION_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLockedRoot(AccessControl.User user) {
        if (!isRoot(user.getId())) {
            return false;
        }
        for (AccessControl.Credential credential : user.getCredentials()) {
            if (credential.getKeyId() != null
                    && credential.getKeyId().startsWith(ROOT_LOCKED_GENERATION_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private AuthenticationResult createUserIdentity(AccessControl.User u) {
        Result<List<AccessControl.Grant>> assembledGrants = mergeGrants(u);
        return switch (assembledGrants) {
            case Result.Failure<List<AccessControl.Grant>>(var code, var message, var throwable) ->
                    AuthenticationResult.failure("User " + u.getId() + " failed to auth: [" + code + "] " + message, throwable);
            case Result.Success<List<AccessControl.Grant>>(var v) ->
                    AuthenticationResult.success(new InternalUserImpl(u.getId(), v));
        };
    }

    private Result<List<AccessControl.Grant>> mergeGrants(AccessControl.User u) {
        List<AccessControl.Grant> l = new ArrayList<>(u.getGrants());

        for (String r : u.getRoles()) {
            List<AccessControl.Role> roles = findRolesByReference(r);
            if (roles.size() != 1) {
                return generalFailure("Number of roles [" + r + "] not " + roles.size());
            } else {
                AccessControl.Role role = roles.getFirst();
                l.addAll(role.getGrants());
                for (String grantReference : role.getGrantReferences()) {
                    List<AccessControl.Grant> grs = findGrantByReference(grantReference);
                    if (grs.size() > 1)
                        return generalFailure("Number of grants [" + grantReference + "] not " + grs.size());
                    l.addAll(grs);
                }
            }
        }
        return new Result.Success<>(l);
    }

    private List<AccessControl.Role> findRolesByReference(String r) {
        return accessControl.get().getRoles().stream().filter(r1 -> r1.getId().equalsIgnoreCase(r)).toList();
    }

    private List<AccessControl.Grant> findGrantByReference(String grantReference) {
        return accessControl.get().getGrants().stream().filter(r1 -> r1.getId().equalsIgnoreCase(grantReference)).toList();
    }

    private boolean performAuthentication(AccessControl.User u, byte[] encodedData) {
        if (u.getCredentials() == null)
            return false;
        for (AccessControl.Credential c : u.getCredentials()) {
            if (credentialMatches(u, c, encodedData)) {
                return true;
            }
        }
        return false;
    }

    private boolean performPasswordAuthentication(AccessControl.User user, byte[] credential) {
        for (AccessControl.Credential candidate : user.getCredentials()) {
            if (candidate != null
                    && (candidate.getType() == AccessControl.CredentialType.ARGON2
                    || candidate.getType() == AccessControl.CredentialType.SHA1)
                    && credentialMatches(user, candidate, credential)) {
                return true;
            }
        }
        return false;
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

    private boolean performPublicKeyAuthentication(AccessControl.User user, byte[] encodedPublicKey) {
        if (user.getCredentials() == null) {
            return false;
        }
        for (AccessControl.Credential credential : user.getCredentials()) {
            if (credential != null
                    && credential.getType() == OPENSSH_PUBLIC_KEY
                    && publicKeysAreEqual(credential.getValue(), encodedPublicKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean credentialMatches(AccessControl.User user, AccessControl.Credential credential, byte[] encodedData) {
        if (credential == null) {
            return false;
        }
        try {
            return valuesAreEqual(credential, encodedData);
        } catch (RuntimeException e) {
            log.warn("Cannot verify {} credential for user '{}'.", credential.getType(), user.getId(), e);
            return false;
        }
    }

    private boolean valuesAreEqual(AccessControl.Credential c, byte[] provided) {
        if (c.getType() == null)
            return false;
        return switch (c.getType()) {
            case OPENSSH_PUBLIC_KEY -> {
                yield publicKeysAreEqual(c.getValue(), provided);
            }
            case SHA1 -> {
                yield orionPasswordHashingService.comparePassword(SHA1, c.getValue(), provided);
            }
            case MD5 -> false;
            case PLAIN -> false;
            case SHA3_256 -> false;
            case ARGON2 -> {
                yield orionPasswordHashingService.comparePassword(ARGON2, c.getValue(), provided);
            }
            case JWT_SIGNING_PUBLIC_KEY -> false;
        };
    }

    private boolean publicKeysAreEqual(String expected, byte[] provided) {
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

    private Result<AccessControl.User> findSingleUser(String userName) {
        ArrayList<AccessControl.User> result = new ArrayList<>();
        consumeUsersById(userName, result::add);
        if (result.size() == 1) {
            return new Result.Success<>(result.getFirst());
        } else {
            return generalFailure("Could't find a single user: <" + userName + "> " + result.size() + " users found.");
        }
    }

    private void consumeUsersById(String userId, Consumer<AccessControl.User> userConsumer) {
        consumeUsersInAccessControl(userId, userConsumer, accessControl.get());
    }

    private static void consumeUsersInAccessControl(String userId, Consumer<AccessControl.User> userConsumer, AccessControl acl) {
        if (acl == null) // could happen as we didn't load ACL yet
            return;
        for (AccessControl.User u : acl.getUsers()) {
            if (u.getId() != null && u.getId().equalsIgnoreCase(userId))
                userConsumer.accept(u);
        }
    }

    private class AccessControlWriter {
        private void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            validateUserUpdate(userUpdate);
            UserEmail author = new UserEmail(userUpdate.id(), userUpdate.email());
            synchronized (reloadLock) {
                AccessControlSnapshot snapshot = switch (loadValidatedAccessControlSnapshot()) {
                    case Result.Success<AccessControlSnapshot>(var loaded) -> loaded;
                    case Result.Failure<AccessControlSnapshot> failure -> throw new IllegalStateException(
                            "Cannot load ACL for user update: [" + failure.code() + "] " + failure.message(),
                            failure.throwable());
                };
                Map<String, AccessControlDraft> drafts = accessControlDrafts(snapshot);
                UserLocation existing = findUserLocation(drafts, userUpdate.id());
                AccessControlDraft owningDraft;
                String owningPath;
                if (existing == null) {
                    owningDraft = primaryDraft(drafts);
                    owningPath = accessControlStorage.primaryPath();
                } else {
                    owningDraft = existing.draft();
                    owningPath = existing.path();
                    owningDraft.getUsers().remove(existing.user());
                }
                owningDraft.getUsers().add(userFrom(userUpdate));
                Map<String, byte[]> updatedFiles = new LinkedHashMap<>(snapshot.files());
                updatedFiles.put(
                        owningPath,
                        serializeAccessControlConfiguration(
                                snapshot,
                                owningPath,
                                owningDraft.toAccessControl()));
                saveAccessControlSnapshotAndReload(
                        new AccessControlSnapshot(updatedFiles, snapshot.version()),
                        "createOrUpdateUser() " + userUpdate.id(),
                        author);
            }
            requestAclUpdateAndWait(author + " createOrUpdateUser() " + userUpdate.id());
        }

        private AccessControlDraft.User userFrom(AccessControlUserUpdate userUpdate) {
            AccessControlDraft.User user = ACLUtil.createUser(userUpdate.id(), userUpdate.email());
            for (AccessControlCredentialUpdate credential : userUpdate.credentials()) {
                user.addCredential(credential.type(), credential.keyId(), credential.value());
            }
            for (AccessControlRepositoryGrantUpdate repositoryGrant : userUpdate.repositories()) {
                addRepositoryGrant(user, repositoryGrant);
            }
            return user;
        }

        private void addRepositoryGrant(AccessControlDraft.User user, AccessControlRepositoryGrantUpdate repositoryGrant) {
            AccessControlDraft.Grant grant = user.addGrant(repositoryGrantId(user.getId(), repositoryGrant.repository()))
                    .addKey(AccessControl.GrantKey.REPOSITORY, repositoryGrant.repository())
                    .addKey(AccessControl.GrantKey.BRANCH, repositoryGrant.branch());
            if (repositoryGrant.read()) {
                grant.addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING);
            }
            if (repositoryGrant.write()) {
                grant.addKey(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING);
            }
            if (repositoryGrant.create()) {
                grant.addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING);
            }
            if (repositoryGrant.force()) {
                grant.addKey(AccessControl.GrantKey.FORCE, AccessControl.TRUE_STRING);
            }
        }

        private String repositoryGrantId(String userId, String repository) {
            return "REPOSITORY_" + safeGrantIdPart(userId) + "_" + safeGrantIdPart(repository);
        }

        private String safeGrantIdPart(String value) {
            return value.replaceAll("[^A-Za-z0-9_.-]", "_");
        }

        private void validateUserUpdate(AccessControlUserUpdate userUpdate) {
            if (userUpdate == null) {
                throw new IllegalArgumentException("User update is required");
            }
            if (userUpdate.id() == null || userUpdate.id().isBlank()) {
                throw new IllegalArgumentException("User id is required");
            }
            for (AccessControlCredentialUpdate credential : userUpdate.credentials()) {
                if (credential.type() == null) {
                    throw new IllegalArgumentException("Credential type is required");
                }
                if (credential.type() == AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY
                        && (credential.keyId() == null || credential.keyId().isBlank())) {
                    throw new IllegalArgumentException("JWT signing key id is required");
                }
                if (credential.value() == null || credential.value().isBlank()) {
                    throw new IllegalArgumentException("Credential value is required");
                }
            }
            for (AccessControlRepositoryGrantUpdate repositoryGrant : userUpdate.repositories()) {
                if (repositoryGrant.repository() == null || repositoryGrant.repository().isBlank()) {
                    throw new IllegalArgumentException("Repository name is required");
                }
            }
        }
    }

    private Result<AccessControlSnapshot> loadValidatedAccessControlSnapshot() {
        return switch (accessControlStorage.load()) {
            case Result.Success<AccessControlSnapshot>(var snapshot) -> switch (documentFrom(snapshot)) {
                case Result.Success<OrionDocument> ignored -> new Result.Success<>(snapshot);
                case Result.Failure<OrionDocument> failure -> new Result.Failure<>(failure);
            };
            case Result.Failure<AccessControlSnapshot> failure -> new Result.Failure<>(failure);
        };
    }

    private Result<AccessControl> accessControlFrom(AccessControlSnapshot snapshot) {
        return switch (documentFrom(snapshot)) {
            case Result.Success<OrionDocument>(var document) ->
                    new Result.Success<>(document.system().accessControl());
            case Result.Failure<OrionDocument> failure -> new Result.Failure<>(failure);
        };
    }

    private Result<OrionDocument> documentFrom(AccessControlSnapshot snapshot) {
        if (snapshot.files().isEmpty()) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }

        AccessControlDraft result = new AccessControlDraft();
        OrionDocument primary = null;
        for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(entry.getValue())) {
                OrionDocument document = xmlService.deserializeDocument(input);
                mergeAccessControl(result, document.system().accessControl());
                if (entry.getKey().equals(accessControlStorage.primaryPath())) {
                    primary = document;
                }
            } catch (IOException e) {
                return new Result.Failure<>(
                        Result.FailureCode.GENERAL,
                        "Cannot parse ACL file " + entry.getKey(),
                        e);
            }
        }
        if (primary == null) {
            return new Result.Failure<>(
                    Result.FailureCode.NOT_FOUND,
                    "Primary ACL configuration file is missing: " + accessControlStorage.primaryPath());
        }
        return new Result.Success<>(primary.replaceAccessControl(result.toAccessControl()));
    }

    private static void mergeAccessControl(AccessControlDraft target, AccessControl source) {
        target.merge(source);
    }

    private void saveAccessControl(AccessControl accessControl, String message, UserEmail author) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            xmlService.serializeDocument(OrionDocument.withAccessControl(accessControl), output);
            accessControlStorage.save(
                    AccessControlSnapshot.singleFile(accessControlStorage.primaryPath(), output.toByteArray()),
                    new AccessControlSaveRequest(message, author));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize ACL", e);
        }
    }

    private void saveAccessControlAndReload(AccessControl accessControl, String message, UserEmail author) {
        saveAccessControl(accessControl, message, author);
        reloadAccessControlOrThrow(author + " " + message);
    }

    private void saveAccessControlSnapshotAndReload(
            AccessControlSnapshot snapshot,
            String message,
            UserEmail author) {
        accessControlStorage.save(snapshot, new AccessControlSaveRequest(message, author));
        reloadAccessControlOrThrow(author + " " + message);
    }

    private void reloadAccessControlOrThrow(String initiator) {
        synchronized (reloadLock) {
            switch (loadValidatedAccessControlSnapshot()) {
                case Result.Success<AccessControlSnapshot>(var loaded) -> prepareAndUpdateAccessControl(loaded);
                case Result.Failure<AccessControlSnapshot> failure -> throw new IllegalStateException(
                        "Cannot reload ACL after " + initiator + ": [" + failure.code() + "] "
                                + failure.message(),
                        failure.throwable());
            }
        }
    }

    private record RootLocation(
            String path,
            AccessControlDraft draft,
            AccessControlDraft.User user) {
    }

    private record UserLocation(
            String path,
            AccessControlDraft draft,
            AccessControlDraft.User user) {
    }

    private record ParsedSshCredential(PublicKey publicKey, SshCredential descriptor) {
    }

    private record ParsedSshCredentials(
            Map<String, ParsedSshCredential> byEncodedKey,
            List<SshCredential> descriptors) {
        private ParsedSshCredentials {
            byEncodedKey = Map.copyOf(byEncodedKey);
            descriptors = List.copyOf(descriptors);
        }
    }

    private static final class InvalidStoredSshKeyException extends RuntimeException {
        private InvalidStoredSshKeyException(Throwable cause) {
            super(cause);
        }
    }

}
