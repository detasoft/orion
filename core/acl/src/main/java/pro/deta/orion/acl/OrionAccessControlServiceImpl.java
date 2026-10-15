package pro.deta.orion.acl;

import pro.deta.orion.util.Subscription;
import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.config.*;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.v2.ConfigurationScope;
import pro.deta.orion.auth.check.ScopedAccess;
import pro.deta.orion.auth.check.GrantMatcher;
import pro.deta.orion.auth.check.MatcherUtils;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.DefaultAccessControl;
import static pro.deta.orion.acl.AccessControlAccess.*;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.Role;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.PlainRootTokenAccess;
import pro.deta.orion.auth.SshKeyEnrollmentAuthentication;
import pro.deta.orion.auth.SshCredentialFailureCode;
import pro.deta.orion.auth.SshCredentialListResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.AccessTokenIdentity;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.auth.TokenRefreshResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.keymaterial.SigningIdentity;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OidcProvider;
import pro.deta.orion.schema.orion.v2.ConfigurationSecret;
import pro.deta.orion.util.Result;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static pro.deta.orion.schema.acl.CredentialType.OPENSSH_PUBLIC_KEY;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.ARGON2;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;
import static pro.deta.orion.util.Result.Failure.generalFailure;

@Slf4j
@Singleton
public class OrionAccessControlServiceImpl implements OrionAccessControlService, ServiceLifecycleStateMachineAdapter.ServiceLifecycle {

    private final OrionPasswordHashingService orionPasswordHashingService;
    private final RootPasswordReset rootPasswordReset;
    private final OrionConfigurationEditor editor;
    private final boolean createDefaultIfMissing;
    private volatile OrionDocumentRevision activeConfiguration;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final AtomicReference<char[]> plainRootToken = new AtomicReference<>();
    private volatile Subscription changeSubscription;

    @Inject
    public OrionAccessControlServiceImpl(
            OrionPasswordHashingService orionPasswordHashingService,
            RootPasswordReset rootPasswordReset,
            SigningIdentity serverIdentity,
            BootstrapConfiguration bootstrapConfiguration,
            OrionConfigurationEditor editor) {
        this.orionPasswordHashingService = orionPasswordHashingService;
        this.rootPasswordReset = rootPasswordReset;
        this.createDefaultIfMissing = bootstrapConfiguration.getBootstrap().getAccessControl()
                .isCreateDefaultIfMissing();
        this.editor = editor;
        this.jwtAccessTokenService = new JwtAccessTokenService(serverIdentity);
    }

    @Override
    public AccessControlAccess createAccess(OrionRevision revision, ChangeMetadata metadata) {
        OrionConfigurationModification modification = editor.edit(revision, metadata);
        return new AccessControlAccess(modification, orionPasswordHashingService);
    }

    private synchronized void loadAccessControlOnStart() {
        try {
            changeSubscription = editor.subscribe(this::onConfigurationChange);
            OrionDocumentRevision read = editor.load();
            switch (read.result()) {
                case Result.Success<OrionDocument> ignored -> {
                    if (rootPasswordReset.enabled()) {
                        resetRootPassword(read);
                    } else {
                        activateConfiguration(read);
                    }
                }
                case Result.Failure<OrionDocument> failure -> {
                    if (failure.code() != Result.FailureCode.NOT_FOUND) {
                        throw new IllegalStateException("Cannot load configuration", failure.throwable());
                    }
                    if (!createDefaultIfMissing) {
                        throw new IllegalStateException(
                                "ACL not found and default ACL creation is disabled.");
                    }
                    resetRootPassword(
                            new OrionDocumentRevision(Result.of(initialDocument()), read.revision()));
                }
            }
        } catch (RuntimeException failure) {
            onStop();
            throw new IllegalStateException("Configuration repository not initialized.", failure);
        }
    }

    @Override
    public void onStart() {
        loadAccessControlOnStart();
    }

    @Override
    public synchronized void onStop() {
        if (changeSubscription != null) {
            changeSubscription.close();
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
        return activeConfiguration != null;
    }

    @Override
    public OrionDocumentRevision configuration() {
        OrionDocumentRevision snapshot = activeConfiguration;
        if (snapshot == null) {
            throw new IllegalStateException("ACL configuration is not initialized");
        }
        return snapshot;
    }

    public ConfigurationStatus configurationStatus() {
        OrionDocumentRevision active = activeConfiguration;
        Optional<String> activeRevision = active == null ? Optional.empty() : revisionValue(active.revision());
        OrionDocumentRevision read = editor.load();
        return switch (read.result()) {
            case Result.Success<OrionDocument> ignored -> new ConfigurationStatus(
                    revisionValue(read.revision()), activeRevision,
                    editor.document(read) instanceof Result.Success<?> ? "valid" : "invalid");
            case Result.Failure<OrionDocument> ignored ->
                    new ConfigurationStatus(
                            revisionValue(read.revision()), activeRevision, "unavailable");
        };
    }

    private static Optional<String> revisionValue(OrionRevision revision) {
        return switch (revision) {
            case OrionRevision.Absent ignored -> Optional.empty();
            case OrionRevision.Stored stored -> Optional.of(stored.value());
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
        return configuration().result()
                .valueOrFailure("Cannot read configuration document")
                .system().accessControl();
    }

    public boolean canAdminister(PrincipalAddress actor,
            Optional<ConfigurationScope> scope) {
        return canAdminister(actor, scope, configuration().result()
                .valueOrFailure("Cannot read configuration document"));
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
            if (!GrantMatcher.of(GrantKey.CONNECTION).matchesAny(grant.info())
                    && GrantMatcher.of(GrantKey.ADMIN).matchesAny(grant.info())) return true;
        }
        return false;
    }

    private static boolean matchesScopedAdministration(List<GrantExpression> expressions,
            ConfigurationScope scope) {
        if (!GrantMatcher.of(GrantKey.ADMIN).matchesAny(expressions)) return false;
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
        OrionDocumentRevision snapshot = editor.load();
        return switch (snapshot.result()) {
            case Result.Failure<OrionDocument> failure -> SshCredentialListResult.failure(
                    SshCredentialFailureCode.PERSISTENCE_FAILED,
                    "Cannot load SSH credentials",
                    failure.throwable());
            case Result.Success<OrionDocument> ignored -> listSshCredentials(snapshot, userId);
        };
    }

    private SshCredentialListResult listSshCredentials(OrionDocumentRevision snapshot, String userId) {
        try {
            User user = findUser(snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl(), userId);
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
                case AuthenticationResult.Success(UserIdentity identity) -> SshKeyEnrollmentAuthentication.success(
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
                if (authenticationGeneration != null && authenticationGeneration.startsWith("oidc:")) {
                    yield verifySystemOidcToken(subject, authenticationGeneration, tokenId);
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

    public User findSystemOidcUser(String issuer, String subject) {
        return findSystemOidcUser(currentAccessControl(), issuer, subject);
    }

    private User findSystemOidcUser(AccessControl snapshot, String issuer, String subject) {
        User found = null;
        for (User user : snapshot.users()) {
            for (Credential credential : user.credentials()) {
                if (credential.type() == CredentialType.OIDC_SUBJECT
                        && Objects.equals(issuer, credential.keyId())
                        && Objects.equals(subject, credential.value())) {
                    if (found != null) {
                        return null;
                    }
                    found = user;
                    break;
                }
            }
        }
        if (found == null || isLockedRoot(found) || rootRecoveryGeneration(found) != null
                || isGenerationAwareRoot(found) && rootAuthenticationGeneration(found) == null) {
            return null;
        }
        return found;
    }

    public Optional<String> systemOidcGeneration(String userId, OidcProvider provider, String subject) {
        return systemOidcGeneration(configuration().result()
                .valueOrFailure("Cannot read configuration document")
                .system(), userId, provider, subject);
    }

    private Optional<String> systemOidcGeneration(OrionDocument.SystemConfiguration system,
            String userId, OidcProvider provider, String subject) {
        User user = findSystemOidcUser(system.accessControl(), provider.issuer().toString(), subject);
        if (user == null || !user.id().equals(userId) || !system.oidcProviders().contains(provider)) {
            return Optional.empty();
        }
        for (ConfigurationSecret secret : system.secrets()) {
            if (secret.id().equals(provider.secret())) {
                String binding = provider + "\n" + secret.envelope() + "\n" + rootAuthenticationGeneration(user);
                return Optional.of("oidc:" + oidcGeneration(binding, subject));
            }
        }
        return Optional.empty();
    }

    public TokenIssueResult issueSystemOidcToken(String userId, OidcProvider provider,
            String subject, long expiresInSeconds) {
        if (expiresInSeconds <= 0 || expiresInSeconds > 3600) {
            return TokenIssueResult.failure("Invalid system OIDC token lifetime");
        }
        Optional<String> generation = systemOidcGeneration(userId, provider, subject);
        if (generation.isEmpty()) return TokenIssueResult.failure("System OIDC account is unavailable");
        try {
            JwtAccessTokenService.IssuedToken token = jwtAccessTokenService.issue(
                    userId, expiresInSeconds, generation.orElseThrow());
            return TokenIssueResult.success(token.value(), token.expiresAtEpochSecond());
        } catch (GeneralSecurityException failure) {
            return TokenIssueResult.failure("Token issue failed", failure);
        }
    }

    private TokenAuthenticationResult verifySystemOidcToken(String userId, String generation, String tokenId) {
        OrionDocument.SystemConfiguration system = configuration().result()
                .valueOrFailure("Cannot read configuration document")
                .system();
        AccessControl snapshot = system.accessControl();
        Result<User> found = findSingleUser(snapshot, userId);
        if (!(found instanceof Result.Success<User>(User user))) {
            return TokenAuthenticationResult.failure("System OIDC account is unavailable");
        }
        for (Credential credential : user.credentials()) {
            if (credential.type() != CredentialType.OIDC_SUBJECT) continue;
            for (OidcProvider provider : system.oidcProviders()) {
                if (!provider.issuer().toString().equals(credential.keyId())) continue;
                if (systemOidcGeneration(system, userId, provider, credential.value())
                        .filter(generation::equals).isEmpty()) {
                    continue;
                }
                AuthenticationResult authenticated = createUserIdentity(snapshot, user);
                if (authenticated instanceof AuthenticationResult.Success(UserIdentity identity)) {
                    return TokenAuthenticationResult.success(identity, new AccessTokenIdentity(tokenId, userId));
                }
            }
        }
        return TokenAuthenticationResult.failure("System OIDC account is unavailable");
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
        for (OrionDocument.Organization organization : configuration().result()
                .valueOrFailure("Cannot read configuration document")
                .organizations()) {
            if (!organization.id().value().equals(organizationId)) {
                continue;
            }
            for (User user : organization.users()) {
                if (!user.id().equals(userId)) {
                    continue;
                }
                for (Credential credential : user.credentials()) {
                    if (credential.type() != CredentialType.OIDC_SUBJECT
                            || !oidcGeneration(credential.keyId(), credential.value()).equals(generation)) {
                        continue;
                    }
                    for (OidcProvider provider : organization.oidcProviders()) {
                        if (provider.issuer().toString().equals(credential.keyId())) {
                            return TokenAuthenticationResult.success(new InternalUserImpl(userId,
                                    organization.id(), () -> configuration().result()
                                            .valueOrFailure("Cannot read configuration document")),
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

    private OrionDocument initialDocument() {
        OrionDocument.Organization organization = new OrionDocument.Organization(
                new OrganizationId("default"), "Default", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()), List.of(organization));
    }

    private synchronized void onConfigurationChange(OrionDocumentRevision snapshot) {
        if (changeSubscription == null || snapshot.revision().equals(configuration().revision())) return;
        try {
            activateConfiguration(snapshot);
        } catch (RuntimeException failure) {
            log.warn("Retaining the last valid ACL configuration after reload failure", failure);
        }
    }

    private void activateConfiguration(OrionDocumentRevision snapshot) {
        OrionDocument document = editor.document(snapshot).valueOrFailure("Invalid ACL configuration");
        activeConfiguration = new OrionDocumentRevision(Result.of(document), snapshot.revision());
    }

    private void resetRootPassword(OrionDocumentRevision snapshot) {
        char[] rootPassword = orionPasswordHashingService.generateRandomString(10);
        try {
            String passwordHash = orionPasswordHashingService.calculateHash(ARGON2, rootPassword);
            String authenticationGeneration = UUID.randomUUID().toString();
            AccessControl current = snapshot.result()
                    .valueOrFailure("Cannot read configuration document")
                    .system().accessControl();
            AccessControl canonical = DefaultAccessControl.create(
                    passwordHash,
                    CredentialType.ARGON2);
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
            OrionConfigurationModification modification = editor.edit(snapshot,
                    new ChangeMetadata(
                            new UserEmail(ROOT_USER_ID, Objects.requireNonNullElse(root.email(), "root@orion.pro")),
                            "root password reset"));
            try {
                OrionDocument prepared = snapshot.result()
                        .valueOrFailure("Cannot read configuration document")
                        .replaceAccessControl(updated);
                modification.modify(prepared);
                modification.apply();
                activateConfiguration(new OrionDocumentRevision(Result.of(prepared), modification.revision()));
            } finally {
                modification.discard();
            }
            printAndClearPlainTextPasswordMessage(System.out, rootPassword);
        } finally {
            Arrays.fill(rootPassword, '\0');
        }
    }

    private boolean idsAreEqual(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
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
                    && (candidate.type() == CredentialType.ARGON2
                    || candidate.type() == CredentialType.SHA1)
                    && credentialMatches(user, candidate, credential)) {
                return true;
            }
        }
        return false;
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

    private Result<User> findSingleUser(AccessControl snapshot, String userName) {
        List<User> result = findUsers(snapshot, userName);
        if (result.size() == 1) {
            return new Result.Success<>(result.getFirst());
        } else {
            return generalFailure("Could't find a single user: <" + userName + "> " + result.size() + " users found.");
        }
    }
}
