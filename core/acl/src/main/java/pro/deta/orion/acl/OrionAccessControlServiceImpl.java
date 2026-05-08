package pro.deta.orion.acl;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.ACLUtil;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.acl.storage.AccessControlSnapshot;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.event.type.RequestToAclUpdate;
import pro.deta.orion.event.type.VolatileUserAdded;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static pro.deta.orion.acl.schema.AccessControl.CredentialType.OPENSSH_PUBLIC_KEY;
import static pro.deta.orion.git.storage.GitBackedInternalStorage.GIT_BACKED_INTERNAL_STORAGE_PRIORITY;
import static pro.deta.orion.util.Result.Failure.generalFailure;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OrionAccessControlServiceImpl implements OrionAccessControlService, OrionApplicationStageEventListener {
    public static final int INIT_PRIORITY = 1;
    private final XmlService xmlService = new XmlService();
    private final AccessControlStorage accessControlStorage;
    private final OrionPasswordHashingService orionPasswordHashingService;
    private final OrionProvider orionProvider;
    private final AtomicReference<AccessControl> accessControl = new AtomicReference<>();
    private final AtomicReference<AccessControl> volatileAccessControl = new AtomicReference<>(new AccessControl());
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @Override
    public void registerToStage(ApplicationStateListenerRegistrar registrar) {
        registrar.register(ApplicationState.INIT, this::onInit).priority(INIT_PRIORITY).waitForCompletion(); // should be completed before GitAccessControlStorage - publishes user to allow in ACL
        registrar.register(ApplicationState.STARTING, this::onStart).priority(GIT_BACKED_INTERNAL_STORAGE_PRIORITY + 2);
    }

    public OrionStageCallResult onInit() {
        orionProvider.getEventManager().registerTypeHandler(VolatileUserAdded.class, (event) -> {
            log.debug("Volatile UserAdded event received: {}", event);
            volatileAccessControl.get().getUsers().add(event.getUserToAdd());
        });
        orionProvider.getEventManager().registerTypeHandler(RequestToAclUpdate.class, (event) -> {
            log.debug("Request to update ACL received: {}", event);
            requestToUpdate();
        });
        return OrionStageCallResult.EMPTY;
    }

    public OrionStageCallResult onStart() {
        OrionStageCallResult orionStageCallResult = OrionStageCallResult.defaultWithWait();
        rwLock.writeLock().lock();
        try {
            Result<AccessControl> loadedAccessControl = loadAccessControl();
            loadedAccessControl.onFailure(f -> {
                orionStageCallResult.submit(orionProvider.getOrionExecutor(), () -> {
                    char[] defaultRootPassword = orionPasswordHashingService.generateRandomString(10);
                    String passwordHash = orionPasswordHashingService.calculateHash(defaultRootPassword);
                    printAndClearPlainTextPasswordMessage(System.out, defaultRootPassword);
                    AccessControl ac = ACLUtil.generateDefaultAccessControl(passwordHash);
                    saveAccessControl(ac, "default scheme applied", UserEmail.EMPTY);
                    updateAccessControl(ac);
                });
            }).onSuccess(this::updateAccessControl);

        } catch (Exception e) {
            log.error("Error while preparing configuration repository.", e);
            throw new IllegalStateException("Configuration repository not initialized.", e);
        } finally {
            rwLock.writeLock().unlock();
        }
        return orionStageCallResult;
    }

    private void printAndClearPlainTextPasswordMessage(PrintStream out, char[] secureChars) {
        out.println();
        out.print("---ROOT PASSWORD: ");
        for (int i = 0; i < secureChars.length; i++) {
            out.print(secureChars[i]);
            secureChars[i] = 0;
        }
        out.println();
    }

    /**
     * Assume to be under rwLock.writeLock
     *
     * @param accessControl
     */
    private void updateAccessControl(AccessControl accessControl) {
        this.accessControl.set(accessControl.unmodify());
    }


    @Override
    public void addKeyToUser(String username, String publicKey) {
        new UnderWriteLock().addKeyToUser(username, publicKey);
    }

    @Override
    public AuthenticationResult authenticateUser(String userName, AccessControl.CredentialType credentialType, byte[] encodedData) {
        Result<AccessControl.User> user = findSingleUser(userName);
        if (user instanceof Result.Success<AccessControl.User>(var u)) {
            if (performAuthentication(u, credentialType, encodedData))
                return createUserIdentity(u);
        }
        log.warn("Attempt to authenticate as {} with public key failed.", userName);
        return AuthenticationResult.failure("authentication failed");
    }

    private void requestToUpdate() {
        try {
            loadAccessControl().onSuccess(this::updateAccessControl);
        } catch (Throwable t) {
            throw t;
        }
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

    private boolean performAuthentication(AccessControl.User u, AccessControl.CredentialType credentialType, byte[] publicKey) {
        for (AccessControl.Credential c : u.getCredentials()) {
            if (c.getType() == credentialType && valuesAreEqual(credentialType, c.getValue(), publicKey))
                return true;
        }
        return false;
    }

    private boolean valuesAreEqual(AccessControl.CredentialType credentialType, String expected, byte[] provided) {
        return switch (credentialType) {
            case OPENSSH_PUBLIC_KEY -> {
                PublicKey authKey = KeyUtils.toRSAPublicKey(provided);
                PublicKey userKey = KeyUtils.readPublicKeyFromString(expected);
                yield org.apache.sshd.common.config.keys.KeyUtils.compareKeys(authKey, userKey);
            }
            case SHA1 -> false;
            case MD5 -> false;
            case PLAIN -> false;
            case SHA3_256 -> false;
            case ARGON2 -> {
                yield orionPasswordHashingService.comparePassword(expected, provided);
            }
        };
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
        consumeUsersInAccessControl(userId, userConsumer, volatileAccessControl.get());
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
            AccessControl ac = r.get();
            updateAccessControl(ac);
        } finally {
            rwLock.writeLock().unlock();
        }
    }


    private class UnderWriteLock {
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
                saveAccessControl(ac, "addKeyToUser() to " + username + postfix, new UserEmail(username, user.getEmail()));
                return ac;
            });
        }

        private AccessControl.User findUserById(AccessControl ac, String username) {
            List<AccessControl.User> users = ac.getUsers().stream().filter(u -> u.getId().equalsIgnoreCase(username)).toList();
            if (users.size() > 1) {
                throw new IllegalStateException("More than a single user found.");
            } else if (users.isEmpty()) {
                throw new IllegalStateException("No users found.");
            } else {
                return users.get(0);
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
}
