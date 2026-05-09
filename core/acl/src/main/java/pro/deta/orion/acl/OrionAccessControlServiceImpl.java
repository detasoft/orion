package pro.deta.orion.acl;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AccessControlCredentialUpdate;
import pro.deta.orion.auth.AccessControlRepositoryGrantUpdate;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.PlainRootTokenAccess;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.crypto.PublicKeysProvider;
import pro.deta.orion.crypto.ServerKeySigner;
import pro.deta.orion.event.type.RequestToAclUpdate;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static pro.deta.orion.acl.schema.AccessControl.CredentialType.OPENSSH_PUBLIC_KEY;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.ARGON2;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;
import static pro.deta.orion.util.Result.Failure.generalFailure;

@Slf4j
@Singleton
public class OrionAccessControlServiceImpl implements OrionAccessControlService, OrionApplicationStageEventListener {
    private static final String ROOT_USER_ID = "root";

    private final XmlService xmlService = new XmlService();
    private final AccessControlStorage accessControlStorage;
    private final OrionPasswordHashingService orionPasswordHashingService;
    private final OrionProvider orionProvider;
    private final OrionConfiguration configuration;
    private final PublicKeysProvider publicKeysProvider;
    private final JwtAccessTokenService jwtAccessTokenService;
    private final AtomicReference<AccessControl> accessControl = new AtomicReference<>();
    private final AtomicReference<char[]> plainRootToken = new AtomicReference<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @Inject
    public OrionAccessControlServiceImpl(
            AccessControlStorage accessControlStorage,
            OrionPasswordHashingService orionPasswordHashingService,
            OrionProvider orionProvider,
            OrionConfiguration configuration,
            PublicKeysProvider publicKeysProvider,
            ServerKeySigner serverKeySigner) {
        this.accessControlStorage = accessControlStorage;
        this.orionPasswordHashingService = orionPasswordHashingService;
        this.orionProvider = orionProvider;
        this.configuration = configuration;
        this.publicKeysProvider = publicKeysProvider;
        this.jwtAccessTokenService = new JwtAccessTokenService(serverKeySigner, publicKeysProvider);
    }

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.task(this, ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, this::aclLoad);
    }

    public OrionStageCallResult aclLoad() {
        orionProvider.getEventManager().registerTypeHandler(RequestToAclUpdate.class, (event) -> {
            log.debug("Request to update ACL received: {}", event);
            requestToUpdate();
        });
        OrionStageCallResult orionStageCallResult = OrionStageCallResult.defaultWithWait();
        try {
            switch (loadAccessControl()) {
                case Result.Success<AccessControl> ignored -> requestAclUpdateAndWait("aclLoad()");
                case Result.Failure<AccessControl> f -> {
                    if (f.code() == Result.FailureCode.NOT_FOUND) {
                        if (!configuration.getBootstrap().getAccessControl().isCreateDefaultIfMissing()) {
                            throw new IllegalStateException("ACL not found and default ACL creation is disabled.");
                        }
                        orionStageCallResult.submit(orionProvider.getOrionExecutor(), () -> {
                            PasswordHashingAlgorithm passwordHashingAlgorithm = defaultPasswordHashingAlgorithm();
                            char[] defaultRootPassword = orionPasswordHashingService.generateRandomString(10);
                            String passwordHash = orionPasswordHashingService.calculateHash(
                                    passwordHashingAlgorithm,
                                    defaultRootPassword);
                            printAndClearPlainTextPasswordMessage(System.out, defaultRootPassword);
                            AccessControl ac = createDefaultAccessControl(
                                    passwordHash,
                                    defaultPasswordCredentialType(passwordHashingAlgorithm));
                            saveAccessControlAndRequestUpdate(ac, "default scheme applied", UserEmail.EMPTY);
                        });
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
        return orionStageCallResult;
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
            throw new IllegalStateException("Plain root token is available only after default ACL creation");
        }
        return token.clone();
    }

    private void updateAccessControl(AccessControl accessControl) {
        this.accessControl.set(accessControl.unmodify());
    }


    @Override
    public void addKeyToUser(String username, String publicKey) {
        new UnderWriteLock().addKeyToUser(username, publicKey);
    }

    @Override
    public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
        new UnderWriteLock().createOrUpdateUser(userUpdate);
    }

    @Override
    public AuthenticationResult authenticateUser(String userName, byte[] encodedData) {
        Result<AccessControl.User> user = findSingleUser(userName);
        if (user instanceof Result.Success<AccessControl.User>(var u)) {
            if (performAuthentication(u, encodedData))
                return createUserIdentity(u);
        }
        log.warn("Attempt to authenticate as '{}' failed.", userName);
        return AuthenticationResult.failure("authentication failed");
    }

    @Override
    public AuthenticationResult authenticateToken(byte[] token) {
        String tokenValue = new String(token, StandardCharsets.UTF_8);
        return switch (jwtAccessTokenService.verify(tokenValue)) {
            case JwtAccessTokenService.VerificationResult.Failure(var reason) ->
                    AuthenticationResult.failure(reason);
            case JwtAccessTokenService.VerificationResult.Success(var subject) -> {
                Result<AccessControl.User> user = findSingleUser(subject);
                if (user instanceof Result.Success<AccessControl.User>(var u)) {
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
            case AuthenticationResult.Success(var userIdentity) -> {
                try {
                    JwtAccessTokenService.IssuedToken token = jwtAccessTokenService.issue(
                            userIdentity.getUserId(),
                            expiresInSeconds);
                    yield TokenIssueResult.success(token.value(), token.expiresAtEpochSecond());
                } catch (RuntimeException e) {
                    yield TokenIssueResult.failure("token issue failed", e);
                }
            }
        };
    }

    private void requestToUpdate() {
        switch (loadAccessControl()) {
            case Result.Success<AccessControl>(var ac) -> prepareAndUpdateAccessControl(ac);
            case Result.Failure<AccessControl> f -> {
                log.error("Error while reloading ACL: [{}] {}", f.code(), f.message(), f.throwable());
                throw new IllegalStateException("ACL cannot be reloaded.", f.throwable());
            }
        }
    }

    private void requestAclUpdateAndWait(String initiator) {
        orionProvider.getEventManager().publishAndWait(new RequestToAclUpdate(initiator));
    }

    private AccessControl createDefaultAccessControl(
            String passwordHash,
            AccessControl.CredentialType passwordCredentialType) {
        AccessControl ac = ACLUtil.generateDefaultAccessControl(passwordHash, passwordCredentialType);
        addInternalServerKeysToRoot(ac);
        return ac;
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

    private void prepareAndUpdateAccessControl(AccessControl ac) {
        if (addInternalServerKeysToRoot(ac)) {
            saveAccessControl(ac, "add internal server keys to root", UserEmail.EMPTY);
        }
        updateAccessControl(ac);
    }

    private boolean addInternalServerKeysToRoot(AccessControl ac) {
        AccessControl.User rootUser = findRootUser(ac);
        if (rootUser == null) {
            return false;
        }

        boolean changed = false;
        for (PublicKey publicKey : publicKeysProvider.getPublicKeys()) {
            if (!hasPublicKeyCredential(rootUser, publicKey)) {
                rootUser.addCredential(OPENSSH_PUBLIC_KEY, KeyUtils.publicKeyToString(publicKey));
                changed = true;
            }
        }
        return changed;
    }

    private AccessControl.User findRootUser(AccessControl ac) {
        for (AccessControl.User user : ac.getUsers()) {
            if (user.getId() != null && ROOT_USER_ID.equalsIgnoreCase(user.getId())) {
                return user;
            }
        }
        return null;
    }

    private boolean hasPublicKeyCredential(AccessControl.User user, PublicKey publicKey) {
        for (AccessControl.Credential credential : user.getCredentials()) {
            if (credential.getType() == OPENSSH_PUBLIC_KEY
                    && publicKeysAreEqual(credential.getValue(), publicKey.getEncoded())) {
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

    private void holdWriteLock(Supplier<AccessControl> r) {
        rwLock.writeLock().lock();
        try {
            r.get();
        } finally {
            rwLock.writeLock().unlock();
        }
    }


    private class UnderWriteLock {
        private void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            validateUserUpdate(userUpdate);
            holdWriteLock(() -> {
                AccessControl ac = accessControl.get().modify();
                ac.getUsers().removeIf(user -> user.getId() != null && user.getId().equalsIgnoreCase(userUpdate.id()));

                AccessControl.User user = ACLUtil.createUser(userUpdate.id(), userUpdate.email());
                for (AccessControlCredentialUpdate credential : userUpdate.credentials()) {
                    user.addCredential(credential.type(), credential.keyId(), credential.value());
                }
                for (AccessControlRepositoryGrantUpdate repositoryGrant : userUpdate.repositories()) {
                    addRepositoryGrant(user, repositoryGrant);
                }

                ac.getUsers().add(user);
                saveAccessControlAndRequestUpdate(ac, "createOrUpdateUser() " + userUpdate.id(), new UserEmail(userUpdate.id(), userUpdate.email()));
                return ac;
            });
        }

        private void addKeyToUser(String username, String publicKey) {
            holdWriteLock(() -> {
                AccessControl ac = accessControl.get().modify();
                AccessControl.User user = findUserById(ac, username);
                user.getCredentials().add(new AccessControl.Credential(OPENSSH_PUBLIC_KEY, publicKey));
                String[] keyParts = publicKey.split("\\s");
                String postfix = "";
                if (keyParts.length == 3) {
                    postfix = " " + keyParts[0] + " " + keyParts[2];
                } else if (keyParts.length == 2) {
                    postfix = " " + keyParts[0];
                }
                saveAccessControlAndRequestUpdate(ac, "addKeyToUser() to " + username + postfix, new UserEmail(username, user.getEmail()));
                return ac;
            });
        }

        private AccessControl.User findUserById(AccessControl ac, String username) {
            List<AccessControl.User> users = ac.getUsers().stream().filter(u -> u.getId() != null && u.getId().equalsIgnoreCase(username)).toList();
            if (users.size() > 1) {
                throw new IllegalStateException("More than a single user found.");
            } else if (users.isEmpty()) {
                throw new IllegalStateException("No users found.");
            } else {
                return users.get(0);
            }
        }

        private void addRepositoryGrant(AccessControl.User user, AccessControlRepositoryGrantUpdate repositoryGrant) {
            AccessControl.Grant grant = user.addGrant(repositoryGrantId(user.getId(), repositoryGrant.repository()))
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

    private Result<AccessControl> loadAccessControl() {
        return switch (accessControlStorage.load()) {
            case Result.Success<AccessControlSnapshot>(var snapshot) -> accessControlFrom(snapshot);
            case Result.Failure<AccessControlSnapshot> failure -> new Result.Failure<>(failure);
        };
    }

    private Result<AccessControl> accessControlFrom(AccessControlSnapshot snapshot) {
        if (snapshot.files().isEmpty()) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }

        AccessControl result = new AccessControl();
        for (Map.Entry<String, byte[]> entry : snapshot.files().entrySet()) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(entry.getValue())) {
                mergeAccessControl(result, xmlService.deserialize(input));
            } catch (IOException e) {
                return new Result.Failure<>(Result.FailureCode.GENERAL, "Cannot parse ACL file " + entry.getKey(), e);
            }
        }
        return new Result.Success<>(result);
    }

    private static void mergeAccessControl(AccessControl target, AccessControl source) {
        target.getUsers().addAll(source.getUsers());
        target.getRoles().addAll(source.getRoles());
        target.getGrants().addAll(source.getGrants());
    }

    private void saveAccessControl(AccessControl accessControl, String message, UserEmail author) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            xmlService.serialize(accessControl, output);
            accessControlStorage.save(
                    AccessControlSnapshot.singleFile(accessControlStorage.primaryPath(), output.toByteArray()),
                    new AccessControlSaveRequest(message, author));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize ACL", e);
        }
    }

    private void saveAccessControlAndRequestUpdate(AccessControl accessControl, String message, UserEmail author) {
        saveAccessControl(accessControl, message, author);
        requestAclUpdateAndWait(author + " " + message);
    }
}
