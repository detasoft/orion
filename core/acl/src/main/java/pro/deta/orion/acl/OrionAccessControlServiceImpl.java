package pro.deta.orion.acl;

import pro.deta.orion.config.ConfigurationFile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.v2.ConfigurationScope;
import pro.deta.orion.auth.check.ScopedAccess;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.MatcherUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.config.OrionConfigurationStorage;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.Role;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AccessControlValidationException;
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
import pro.deta.orion.auth.AccessTokenIdentity;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.config.OrionConfigurationEdit;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.keymaterial.ServerIdentityCapability;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OidcProvider;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.Result;
import pro.deta.orion.schema.orion.OrionXml;

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

    private final OrionConfigurationStorage configurationStorage;
    private final OrionPasswordHashingService orionPasswordHashingService;
    private final OrionRuntimeOptions runtimeOptions;
    private final ServerIdentityCapability serverIdentity;
    private final OrionDesiredState desiredState;
    private final OrionConfigurationEditor editor;
    private OrionConfigurationStorage.ChangeSubscription preparationSubscription;
    private final Optional<ConfigurationFile> initialConfiguration;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final AtomicReference<char[]> plainRootToken = new AtomicReference<>();
    private volatile OrionConfigurationStorage.ChangeSubscription changeSubscription;

    @Inject
    public OrionAccessControlServiceImpl(
            OrionConfigurationStorage configurationStorage,
            OrionPasswordHashingService orionPasswordHashingService,
            OrionRuntimeOptions runtimeOptions,
            ServerIdentityCapability serverIdentity,
            OrionDesiredState desiredState,
            OrionConfigurationEditor editor,
            Optional<ConfigurationFile> initialConfiguration) {
        this.configurationStorage = configurationStorage;
        this.orionPasswordHashingService = orionPasswordHashingService;
        this.runtimeOptions = runtimeOptions;
        this.serverIdentity = serverIdentity;
        this.desiredState = desiredState;
        this.editor = editor;
        this.initialConfiguration = Objects.requireNonNull(initialConfiguration, "initial configuration");
        this.jwtAccessTokenService = new JwtAccessTokenService(serverIdentity);
    }

    private void loadAccessControlOnStart() {
        try {
            preparationSubscription = editor.onPrepare("add internal server keys to root", this::prepareAccessControl);
            changeSubscription = configurationStorage.onChange(initiator -> requestToUpdate());
            synchronized (editor) {
                Result<ConfigurationFile> initial = initialConfiguration
                        .<Result<ConfigurationFile>>map(this::validateConfigurationFile)
                        .orElseGet(this::loadValidatedConfigurationFile);
                switch (initial) {
                    case Result.Success<ConfigurationFile>(var file) -> {
                        if (runtimeOptions.resetRootPassword()) {
                            resetRootPassword(file);
                        } else {
                            editor.reload(file);
                        }
                    }
                    case Result.Failure<ConfigurationFile> f -> {
                        if (f.code() == Result.FailureCode.NOT_FOUND) {
                            if (!configurationStorage.createIfMissing()) {
                                throw new IllegalStateException(
                                        "ACL not found and default ACL creation is disabled.");
                            }
                            resetRootPassword(new ConfigurationFile(
                                    serializeInitialConfiguration(new AccessControl()), Optional.empty()));
                        } else {
                            log.error("Error while preparing configuration repository.", f.throwable());
                            throw new IllegalStateException(
                                    "Configuration repository not initialized.", f.throwable());
                        }
                    }
                }
                if (initialConfiguration.isPresent()) {
                    Result<ConfigurationFile> latest = configurationStorage.load();
                    if (latest instanceof Result.Success<ConfigurationFile> success
                            && !success.value().revision().equals(desiredState.current().revision())) {
                        requestToUpdate();
                    }
                }
            }
        } catch (Exception e) {
            onStop();
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
        OrionConfigurationStorage.ChangeSubscription subscription = changeSubscription;
        if (subscription != null) {
            subscription.close();
            changeSubscription = null;
        }
        if (preparationSubscription != null) {
            preparationSubscription.close();
            preparationSubscription = null;
        }
        // ACL state remains available until process shutdown.
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        return desiredState.isPublished();
    }

    public ConfigurationStatus configurationStatus() {
        Result<ConfigurationFile> stored = configurationStorage.load();
        Optional<String> activeRevision = desiredState.current().revision();
        return switch (stored) {
            case Result.Success<ConfigurationFile>(var file) -> new ConfigurationStatus(
                    file.revision(), activeRevision,
                    validateConfigurationFile(file) instanceof Result.Success<?> ? "valid" : "invalid");
            case Result.Failure<ConfigurationFile> ignored ->
                    new ConfigurationStatus(Optional.empty(), activeRevision, "unavailable");
        };
    }

    public record ConfigurationStatus(
            Optional<String> storedRevision, Optional<String> activeRevision, String validation) {
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

    private AccessControl currentAccessControl() {
        return desiredState.current().document().system().accessControl();
    }

    public boolean canAdminister(PrincipalAddress actor,
            Optional<ConfigurationScope> scope) {
        return canAdminister(actor, scope, desiredState.current().document());
    }

    public boolean canAdminister(PrincipalAddress actor, Optional<ConfigurationScope> scope,
            OrionDocument document) {
        if (actor instanceof PrincipalAddress.OrganizationPrincipalAddress org) {
            if (scope.isEmpty() || !scope.orElseThrow().organizationId().equals(org.organizationId())) return false;
            for (OrionDocument.Organization organization : document.organizations()) {
                if (organization.id().equals(org.organizationId())) {
                    return ScopedAccess.allows(organization, org.userId(),
                            scope.orElseThrow(), expressions -> matchesScopedAdministration(
                                    expressions, scope.orElseThrow()));
                }
            }
            return false;
        }
        AccessControl snapshot = document.system().accessControl();
        if (!(findSingleUser(snapshot, actor.userId().value())
                instanceof Result.Success<User>(var user)) || isLockedRoot(user)) return false;
        Result<List<Grant>> grants = mergeGrants(snapshot, user);
        if (!(grants instanceof Result.Success<List<Grant>>(var assigned))) return false;
        for (Grant grant : assigned) {
            if (!GrantMatcher.of(AccessControl.GrantKey.CONNECTION).matchesAny(grant.info())
                    && GrantMatcher.of(AccessControl.GrantKey.ADMIN).matchesAny(grant.info())) return true;
        }
        return false;
    }

    private static boolean matchesScopedAdministration(List<GrantExpression> expressions,
            ConfigurationScope scope) {
        if (!GrantMatcher.of(AccessControl.GrantKey.ADMIN).matchesAny(expressions)) return false;
        boolean repositoryRestricted = false;
        boolean repositoryMatches = false;
        for (GrantExpression expression : expressions) {
            switch (expression.key()) {
                case REPOSITORY -> {
                    repositoryRestricted = true;
                    repositoryMatches |= scope.repositoryId().isPresent()
                            && MatcherUtils.matchExpressionValue(expression.value(), scope.toString());
                }
                case CONNECTION, BRANCH, NETWORK_SOURCE, NETWORK_PORT -> {
                    return false;
                }
                default -> { }
            }
        }
        return !repositoryRestricted || repositoryMatches;
    }


    @Override
    public SshCredentialListResult listSshCredentials(String userId) {
        if (userId == null || userId.isBlank()) {
            return SshCredentialListResult.failure(
                    SshCredentialFailureCode.USER_NOT_FOUND,
                    "User is not available");
        }
        synchronized (editor) {
            return switch (configurationStorage.load()) {
                case Result.Failure<ConfigurationFile> failure -> SshCredentialListResult.failure(
                        SshCredentialFailureCode.PERSISTENCE_FAILED,
                        "Cannot load SSH credentials",
                        failure.throwable());
                case Result.Success<ConfigurationFile>(var snapshot) -> listSshCredentials(snapshot, userId);
            };
        }
    }

    private SshCredentialListResult listSshCredentials(ConfigurationFile snapshot, String userId) {
        try {
            User user = findUser(parseAccessControlConfiguration(snapshot.content()), userId);
            if (user == null) {
                return SshCredentialListResult.failure(
                        SshCredentialFailureCode.USER_NOT_FOUND,
                        "User is not available");
            }
            return SshCredentialListResult.success(sshCredentials(user).descriptors());
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
    public SshCredentialUpdateResult addSshCredentials(OrionConfigurationEdit edit, String userId, List<String> publicKeys) {
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

        return stageSshCredentials(edit, userId, parsedKeys);
    }

    private SshCredentialUpdateResult stageSshCredentials(
            OrionConfigurationEdit edit,
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

    @Override
    public SshCredentialUpdateResult removeSshCredential(
            OrionConfigurationEdit edit,
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
        return stageSshCredentialRemoval(edit, userId, prefix, force);
    }

    private SshCredentialUpdateResult stageSshCredentialRemoval(
            OrionConfigurationEdit edit,
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

    @Override
    public void createOrUpdateUser(OrionConfigurationEdit edit, AccessControlUserUpdate userUpdate) {
        new AccessControlWriter().createOrUpdateUser(edit, userUpdate);
    }

    @Override
    public boolean userExists(String userName) {
        return findSingleUser(currentAccessControl(), userName) instanceof Result.Success<?>;
    }

    @Override
    public AuthenticationResult authenticateUser(String userName, byte[] encodedData) {
        AccessControl snapshot = currentAccessControl();
        Result<User> user = findSingleUser(snapshot, userName);
        if (user instanceof Result.Success<User>(var u)) {
            if (isLockedRoot(u)) {
                return AuthenticationResult.failure("authentication failed");
            }
            if (rootRecoveryGeneration(u) != null) {
                log.warn("Attempt to use the root recovery password outside SSH key enrollment.");
                return AuthenticationResult.failure("authentication failed");
            }
            if (performAuthentication(u, encodedData))
                return createUserIdentity(snapshot, u);
        }
        log.warn("Attempt to authenticate as '{}' failed.", userName);
        return AuthenticationResult.failure("authentication failed");
    }

    @Override
    public SshKeyEnrollmentAuthentication authenticateSshKeyEnrollment(
            String userName,
            byte[] credential) {
        AccessControl snapshot = currentAccessControl();
        Result<User> user = findSingleUser(snapshot, userName);
        if (user instanceof Result.Success<User>(var matchedUser)
                && !isLockedRoot(matchedUser)
                && performPasswordAuthentication(matchedUser, credential)) {
            String recoveryGeneration = rootRecoveryGeneration(matchedUser);
            if (isGenerationAwareRoot(matchedUser) && recoveryGeneration == null) {
                return SshKeyEnrollmentAuthentication.failure("authentication failed");
            }
            return switch (createUserIdentity(snapshot, matchedUser)) {
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
            OrionConfigurationEdit edit,
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

        return stageRootSshKeyEnrollment(edit, expectedGeneration, parsedKeys);
    }

    private SshKeyEnrollmentResult stageRootSshKeyEnrollment(
            OrionConfigurationEdit edit,
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

    @Override
    public AuthenticationResult authenticateSshUser(String userName, byte[] encodedPublicKey) {
        AccessControl snapshot = currentAccessControl();
        Result<User> user = findSingleUser(snapshot, userName);
        if (user instanceof Result.Success<User>(var matchedUser)
                && !isLockedRoot(matchedUser)
                && performPublicKeyAuthentication(matchedUser, encodedPublicKey)) {
            return createUserIdentity(snapshot, matchedUser);
        }
        log.warn("SSH public-key authentication as '{}' failed.", userName);
        return AuthenticationResult.failure("authentication failed");
    }

    @Override
    public AuthenticationResult authenticateGitSshKey(byte[] encodedPublicKey) {
        List<User> matchingUsers = new ArrayList<>();
        AccessControl currentAccessControl = currentAccessControl();
        if (currentAccessControl != null) {
            for (User user : currentAccessControl.users()) {
                if (!isLockedRoot(user) && performPublicKeyAuthentication(user, encodedPublicKey)) {
                    matchingUsers.add(user);
                }
            }
        }
        if (matchingUsers.size() == 1) {
            return createUserIdentity(currentAccessControl, matchingUsers.getFirst());
        }
        log.warn("Git SSH public key resolved to {} users.", matchingUsers.size());
        return AuthenticationResult.failure("authentication failed");
    }

    @Override
    public TokenAuthenticationResult verifyToken(byte[] token) {
        String tokenValue = new String(token, StandardCharsets.UTF_8);
        return switch (jwtAccessTokenService.verify(tokenValue)) {
            case JwtAccessTokenService.VerificationResult.Failure(var reason) ->
                    TokenAuthenticationResult.failure(reason);
            case JwtAccessTokenService.VerificationResult.Success(
                    var subject,
                    var authenticationGeneration,
                    var tokenId, var organization) -> {
                if (organization != null) {
                    yield verifyOrganizationToken(organization, subject, authenticationGeneration, tokenId);
                }
                AccessControl snapshot = currentAccessControl();
                Result<User> user = findSingleUser(snapshot, subject);
                if (user instanceof Result.Success<User>(var u)) {
                    if (isLockedRoot(u)) {
                        yield TokenAuthenticationResult.failure("authentication failed");
                    }
                    String currentGeneration = rootAuthenticationGeneration(u);
                    if (isGenerationAwareRoot(u)
                            && (currentGeneration == null || !currentGeneration.equals(authenticationGeneration))) {
                        yield TokenAuthenticationResult.failure("authentication failed");
                    }
                    AuthenticationResult authenticated = createUserIdentity(snapshot, u);
                    if (authenticated instanceof AuthenticationResult.Success(var userIdentity)) {
                        yield TokenAuthenticationResult.success(
                                userIdentity,
                                new AccessTokenIdentity(tokenId, subject));
                    }
                    yield TokenAuthenticationResult.failure("authentication failed");
                }
                yield TokenAuthenticationResult.failure("authentication failed");
            }
        };
    }

    public TokenIssueResult issueOrganizationToken(OrganizationId organizationId, String userId,
            String issuer, String subject, long expiresInSeconds) {
        if (expiresInSeconds <= 0 || expiresInSeconds > 3600) {
            return TokenIssueResult.failure("Invalid organization token lifetime");
        }
        String generation = oidcGeneration(issuer, subject);
        TokenAuthenticationResult authentication = verifyOrganizationToken(
                organizationId.value(), userId, generation, "pending");
        if (!(authentication instanceof TokenAuthenticationResult.Success)) {
            return TokenIssueResult.failure("Organization account is unavailable");
        }
        try {
            JwtAccessTokenService.IssuedToken token = jwtAccessTokenService.issue(
                    userId, expiresInSeconds, generation, organizationId.value());
            return TokenIssueResult.success(token.value(), token.expiresAtEpochSecond());
        } catch (GeneralSecurityException failure) {
            return TokenIssueResult.failure("Token issue failed", failure);
        }
    }

    private TokenAuthenticationResult verifyOrganizationToken(String organizationId, String userId,
            String generation, String tokenId) {
        for (OrionDocument.Organization organization : desiredState.current().document().organizations()) {
            if (!organization.id().value().equals(organizationId)) {
                continue;
            }
            for (User user : organization.users()) {
                if (!user.id().equals(userId)) {
                    continue;
                }
                for (Credential credential : user.credentials()) {
                    if (credential.type() != AccessControl.CredentialType.OIDC_SUBJECT
                            || !oidcGeneration(credential.keyId(), credential.value()).equals(generation)) {
                        continue;
                    }
                    for (OidcProvider provider : organization.oidcProviders()) {
                        if (provider.issuer().toString().equals(credential.keyId())) {
                            return TokenAuthenticationResult.success(new InternalUserImpl(userId,
                                    organization.id(), () -> desiredState.current().document()),
                                    new AccessTokenIdentity(tokenId, organizationId + "/" + userId));
                        }
                    }
                }
            }
        }
        return TokenAuthenticationResult.failure("Organization account is unavailable");
    }

    private static String oidcGeneration(String issuer, String subject) {
        try {
            byte[] bytes = (issuer + "\n" + subject).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds) {
        return switch (authenticateUser(userName, credential)) {
            case AuthenticationResult.Failure(var reason, var throwable) ->
                    TokenIssueResult.failure(reason, throwable);
            case AuthenticationResult.Success(var userIdentity) -> issueToken(userIdentity, expiresInSeconds);
        };
    }

    @Override
    public TokenRefreshResult refreshToken(
            AuthenticationResult.Success renewalAuthority,
            long expiresInSeconds) {
        if (renewalAuthority == null) {
            return TokenRefreshResult.failure("primary authentication is required");
        }
        return switch (issueToken(renewalAuthority.userIdentity(), expiresInSeconds)) {
            case TokenIssueResult.Success(var token, var expiresAtEpochSecond) ->
                    TokenRefreshResult.success(token, expiresAtEpochSecond);
            case TokenIssueResult.Failure(var reason, var throwable) ->
                    TokenRefreshResult.failure(reason, throwable);
        };
    }

    private TokenIssueResult issueToken(UserIdentity userIdentity, long expiresInSeconds) {
        if (userIdentity == null
                || userIdentity.isAnonymous()
                || userIdentity.getUserId() == null
                || userIdentity.getUserId().isBlank()) {
            return TokenIssueResult.failure("authenticated user is required");
        }
        if (userIdentity.getOrganizationId().isPresent()) {
            return TokenIssueResult.failure("system identity is required for system token issue");
        }
        Result<User> user = findSingleUser(currentAccessControl(), userIdentity.getUserId());
        if (user instanceof Result.Failure<User>(var code, var message, var throwable)) {
            return TokenIssueResult.failure("user is not available for token issue", throwable);
        }
        if (!(user instanceof Result.Success<User>(var currentUser))) {
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
    public ConfigurationFile accessControlConfigurationFile() {
        return switch (configurationStorage.load()) {
            case Result.Success<ConfigurationFile>(var file) -> new ConfigurationFile(
                    serializeOrionConfiguration(parseOrionConfiguration(file.content())), file.revision());
            case Result.Failure<ConfigurationFile> failure ->
                    throw new IllegalStateException("Cannot load ACL configuration file", failure.throwable());
        };
    }

    private AccessControl parseAccessControlConfiguration(byte[] content) {
        return parseOrionConfiguration(content).system().accessControl();
    }

    private OrionDocument parseOrionConfiguration(byte[] content) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return OrionXml.read(input);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid ACL configuration file", e);
        }
    }

    private byte[] serializeInitialConfiguration(AccessControl accessControl) {
        OrionDocument.Organization organization = new OrionDocument.Organization(
                new OrganizationId("default"), "Default", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        return serializeOrionConfiguration(new OrionDocument(
                new OrionDocument.SystemConfiguration(accessControl), List.of(organization)));
    }

    private byte[] serializeOrionConfiguration(OrionDocument document) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            OrionXml.write(document, output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize ACL configuration file", e);
        }
    }

    private void requestToUpdate() {
        try {
            editor.onStorageChange();
        } catch (RuntimeException failure) {
            log.error("Retaining the last valid configuration after reload failure", failure);
        }
    }

    private void resetRootPassword(ConfigurationFile snapshot) {
        char[] rootPassword = orionPasswordHashingService.generateRandomString(10);
        try {
            String passwordHash = orionPasswordHashingService.calculateHash(ARGON2, rootPassword);
            String authenticationGeneration = UUID.randomUUID().toString();
            AccessControl current = parseAccessControlConfiguration(snapshot.content());
            AccessControl canonical = ACLUtil.generateDefaultAccessControl(
                    passwordHash,
                    AccessControl.CredentialType.ARGON2);
            User canonicalRoot = canonical.users().getFirst();
            Credential password = canonicalRoot.credentials().getFirst();
            User root = withCredentials(canonicalRoot, List.of(new Credential(
                    password.type(), generationKeyId(authenticationGeneration), password.value())));
            List<User> users = new ArrayList<>(current.users());
            users.removeIf(user -> isRoot(user.id()));
            users.add(root);
            List<Role> roles = new ArrayList<>(current.roles());
            for (Role canonicalRole : canonical.roles()) {
                roles.removeIf(role -> idsAreEqual(role.id(), canonicalRole.id()));
                roles.add(canonicalRole);
            }
            List<Grant> grants = new ArrayList<>(current.grants());
            for (Grant canonicalGrant : canonical.grants()) {
                grants.removeIf(grant -> idsAreEqual(grant.id(), canonicalGrant.id()));
                grants.add(canonicalGrant);
            }
            AccessControl updated = new AccessControl(users, roles, grants);
            editor.edit(snapshot).update(document -> document.replaceAccessControl(updated)).apply(
                    "root password reset",
                    new UserEmail(ROOT_USER_ID, Objects.requireNonNullElse(root.email(), "root@orion.pro")));
            printAndClearPlainTextPasswordMessage(System.out, rootPassword);
        } finally {
            Arrays.fill(rootPassword, '\0');
        }
    }

    private List<User> rootUsers(AccessControl acl) {
        List<User> roots = new ArrayList<>();
        for (User user : acl.users()) {
            if (isRoot(user.id())) {
                roots.add(user);
            }
        }
        return List.copyOf(roots);
    }

    private boolean idsAreEqual(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private ConfigurationFile prepareAccessControl(ConfigurationFile loadedSnapshot) {
        AccessControl acl = parseAccessControlConfiguration(loadedSnapshot.content());
        List<User> roots = rootUsers(acl);
        if (roots.size() == 1) {
            User root = roots.getFirst();
            List<Credential> credentials = synchronizedInternalServerKeys(root);
            if (credentials == null) return loadedSnapshot;
            OrionDocument document = parseOrionConfiguration(loadedSnapshot.content());
            return new ConfigurationFile(
                    serializeOrionConfiguration(document.replaceAccessControl(
                            replaceUser(acl, root, withCredentials(root, credentials)))),
                    loadedSnapshot.revision());
        }
        return loadedSnapshot;
    }

    private List<Credential> synchronizedInternalServerKeys(User rootUser) {
        if (isGenerationAwareRoot(rootUser)) {
            return null;
        }

        List<Credential> credentials = new ArrayList<>(rootUser.credentials());
        boolean changed = removeRetainedServerKeys(credentials);
        for (PublicKey publicKey : serverPublicKeys()) {
            if (!hasPublicKeyCredential(credentials, publicKey)) {
                credentials.add(new Credential(OPENSSH_PUBLIC_KEY,
                        KeyUtils.publicKeyToString(publicKey)));
                changed = true;
            }
        }
        return changed ? credentials : null;
    }

    private boolean removeRetainedServerKeys(List<Credential> credentials) {
        Set<String> retained = new HashSet<>();
        for (PublicKey publicKey : retainedServerPublicKeys()) {
            retained.add(KeyUtils.publicKeyToString(publicKey));
        }
        if (retained.isEmpty()) {
            return false;
        }
        return credentials.removeIf(credential ->
                credential.type() == OPENSSH_PUBLIC_KEY
                        && retained.contains(credential.value()));
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

    private static String rootRecoveryGeneration(User user) {
        if (!isRoot(user.id()) || user.credentials().size() != 1) {
            return null;
        }
        Credential credential = user.credentials().getFirst();
        return credential.type() == AccessControl.CredentialType.ARGON2
                ? generationFromKeyId(credential.keyId())
                : null;
    }

    private static String rootAuthenticationGeneration(User user) {
        if (!isRoot(user.id()) || user.credentials().isEmpty()) {
            return null;
        }
        AccessControl.CredentialType expectedType = user.credentials().size() == 1
                && user.credentials().getFirst().type() == AccessControl.CredentialType.ARGON2
                ? AccessControl.CredentialType.ARGON2
                : OPENSSH_PUBLIC_KEY;
        String generation = null;
        for (Credential credential : user.credentials()) {
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

    private static boolean isGenerationAwareRoot(User user) {
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

    private static boolean isRoot(String userId) {
        return userId != null && ROOT_USER_ID.equalsIgnoreCase(userId);
    }

    private boolean hasPublicKeyCredential(List<Credential> credentials, PublicKey publicKey) {
        for (Credential credential : credentials) {
            if (credential.type() == OPENSSH_PUBLIC_KEY
                    && publicKeysAreEqual(credential.value(), publicKey.getEncoded())) {
                return true;
            }
        }
        return false;
    }

    private User findUser(AccessControl acl, String userId) {
        User matched = null;
        for (User user : acl.users()) {
            if (user.id() != null && user.id().equalsIgnoreCase(userId)) {
                if (matched != null) {
                    throw new IllegalStateException("More than one user matches the requested id");
                }
                matched = user;
            }
        }
        return matched;
    }

    private User withCredentials(
            User user, List<Credential> credentials) {
        return new User(user.id(), user.first(), user.last(), user.email(),
                credentials, user.roles(), user.grants());
    }

    private AccessControl replaceUser(
            AccessControl acl, User current, User replacement) {
        List<User> users = new ArrayList<>(acl.users());
        users.set(users.indexOf(current), replacement);
        return new AccessControl(users, acl.roles(), acl.grants());
    }

    private ParsedSshCredentials sshCredentials(User user) {
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
                    AccessControl.CredentialType.ARGON2,
                    ROOT_LOCKED_GENERATION_PREFIX + UUID.randomUUID(),
                    markerHash));
        } finally {
            Arrays.fill(markerSecret, '\0');
        }
    }

    private static boolean isLockedRoot(User user) {
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

    private AuthenticationResult createUserIdentity(AccessControl snapshot, User u) {
        Result<List<Grant>> assembledGrants = mergeGrants(snapshot, u);
        return switch (assembledGrants) {
            case Result.Failure<List<Grant>>(var code, var message, var throwable) ->
                    AuthenticationResult.failure("User " + u.id() + " failed to auth: [" + code + "] " + message, throwable);
            case Result.Success<List<Grant>>(var v) ->
                    AuthenticationResult.success(new InternalUserImpl(u.id(), v));
        };
    }

    private Result<List<Grant>> mergeGrants(AccessControl snapshot, User u) {
        List<Grant> l = new ArrayList<>(u.grants());

        for (String r : u.roles()) {
            List<Role> roles = findRolesByReference(snapshot, r);
            if (roles.size() != 1) {
                return generalFailure("Number of roles [" + r + "] not " + roles.size());
            } else {
                Role role = roles.getFirst();
                l.addAll(role.grants());
                for (String grantReference : role.grantReferences()) {
                    List<Grant> grs = findGrantByReference(snapshot, grantReference);
                    if (grs.size() > 1)
                        return generalFailure("Number of grants [" + grantReference + "] not " + grs.size());
                    l.addAll(grs);
                }
            }
        }
        return new Result.Success<>(l);
    }

    private List<Role> findRolesByReference(AccessControl snapshot, String r) {
        return snapshot.roles().stream().filter(r1 -> r1.id().equalsIgnoreCase(r)).toList();
    }

    private List<Grant> findGrantByReference(AccessControl snapshot, String grantReference) {
        return snapshot.grants().stream().filter(r1 -> r1.id().equalsIgnoreCase(grantReference)).toList();
    }

    private boolean performAuthentication(User u, byte[] encodedData) {
        if (u.credentials() == null)
            return false;
        for (Credential c : u.credentials()) {
            if (credentialMatches(u, c, encodedData)) {
                return true;
            }
        }
        return false;
    }

    private boolean performPasswordAuthentication(User user, byte[] credential) {
        for (Credential candidate : user.credentials()) {
            if (candidate != null
                    && (candidate.type() == AccessControl.CredentialType.ARGON2
                    || candidate.type() == AccessControl.CredentialType.SHA1)
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

    private boolean performPublicKeyAuthentication(User user, byte[] encodedPublicKey) {
        if (user.credentials() == null) {
            return false;
        }
        for (Credential credential : user.credentials()) {
            if (credential != null
                    && credential.type() == OPENSSH_PUBLIC_KEY
                    && publicKeysAreEqual(credential.value(), encodedPublicKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean credentialMatches(User user, Credential credential, byte[] encodedData) {
        if (credential == null) {
            return false;
        }
        try {
            return valuesAreEqual(credential, encodedData);
        } catch (RuntimeException e) {
            log.warn("Cannot verify {} credential for user '{}'.", credential.type(), user.id(), e);
            return false;
        }
    }

    private boolean valuesAreEqual(Credential c, byte[] provided) {
        if (c.type() == null)
            return false;
        return switch (c.type()) {
            case OPENSSH_PUBLIC_KEY -> {
                yield publicKeysAreEqual(c.value(), provided);
            }
            case SHA1 -> {
                yield orionPasswordHashingService.comparePassword(SHA1, c.value(), provided);
            }
            case MD5 -> false;
            case PLAIN -> false;
            case SHA3_256 -> false;
            case ARGON2 -> {
                yield orionPasswordHashingService.comparePassword(ARGON2, c.value(), provided);
            }
            case JWT_SIGNING_PUBLIC_KEY, OIDC_SUBJECT -> false;
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

    private Result<User> findSingleUser(AccessControl snapshot, String userName) {
        ArrayList<User> result = new ArrayList<>();
        consumeUsersInAccessControl(userName, result::add, snapshot);
        if (result.size() == 1) {
            return new Result.Success<>(result.getFirst());
        } else {
            return generalFailure("Could't find a single user: <" + userName + "> " + result.size() + " users found.");
        }
    }

    private static void consumeUsersInAccessControl(String userId, Consumer<User> userConsumer, AccessControl acl) {
        if (acl == null) // could happen as we didn't load ACL yet
            return;
        for (User u : acl.users()) {
            if (u.id() != null && u.id().equalsIgnoreCase(userId))
                userConsumer.accept(u);
        }
    }

    private class AccessControlWriter {
        private void createOrUpdateUser(OrionConfigurationEdit edit, AccessControlUserUpdate userUpdate) {
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
                    AccessControl.GrantKey.REPOSITORY, repositoryGrant.repository()));
            expressions.add(new GrantExpression(
                    AccessControl.GrantKey.BRANCH, repositoryGrant.branch()));
            if (repositoryGrant.read()) {
                expressions.add(new GrantExpression(
                        AccessControl.GrantKey.READ, AccessControl.TRUE_STRING));
            }
            if (repositoryGrant.readWrite()) {
                expressions.add(new GrantExpression(
                        AccessControl.GrantKey.READ_WRITE, AccessControl.TRUE_STRING));
            }
            if (repositoryGrant.create()) {
                expressions.add(new GrantExpression(
                        AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING));
            }
            if (repositoryGrant.force()) {
                expressions.add(new GrantExpression(
                        AccessControl.GrantKey.FORCE, AccessControl.TRUE_STRING));
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
                if (credential.type() == AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY
                        && (credential.keyId() == null || credential.keyId().isBlank())) {
                    throw new AccessControlValidationException("JWT signing key id is required");
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
    }

    private Result<ConfigurationFile> loadValidatedConfigurationFile() {
        return switch (configurationStorage.load()) {
            case Result.Success<ConfigurationFile>(var file) -> validateConfigurationFile(file);
            case Result.Failure<ConfigurationFile> failure -> new Result.Failure<>(failure);
        };
    }

    private Result<ConfigurationFile> validateConfigurationFile(ConfigurationFile file) {
        return switch (documentFrom(file)) {
            case Result.Success<OrionDocument> ignored -> new Result.Success<>(file);
            case Result.Failure<OrionDocument> failure -> new Result.Failure<>(failure);
        };
    }

    private Result<OrionDocument> documentFrom(ConfigurationFile snapshot) {
        return editor.document(snapshot);
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
