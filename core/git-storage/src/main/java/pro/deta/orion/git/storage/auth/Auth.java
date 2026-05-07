package pro.deta.orion.git.storage.auth;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.util.KeyUtils;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface Auth {

    record HttpAuth(String username, String password) implements Auth {
    }

    record SshAuthKey(String username, Path key, Optional<KeyPair> keyPair) implements Auth {
        public SshAuthKey(String username, Path key) {
            this(username, key, Optional.ofNullable(KeyUtils.readKeyFromFile(key).valueOrWarning("Can't read private key")));
        }
    }

    record SshAuthKeyPair(String username, Optional<KeyPair> keyPair) implements Auth {
    }

    record LocalSshAuthKeyPair(String username, Optional<KeyPair> keyPair, List<PublicKey> publicKeys, String localRepositoryName) implements Auth {
    } // for authentication to itself with ssh-key

    record LocalNoneAuth(String localRepositoryName) implements Auth {
    }

    default String getUsername() {
        return switch (this) {
            case HttpAuth httpAuth -> httpAuth.username();
            case LocalNoneAuth noneAuth -> "LOCAL_AUTH_EMPTY";
            case SshAuthKey sshAuthKey -> sshAuthKey.username();

            case SshAuthKeyPair sshAuthKeyPair -> sshAuthKeyPair.username();
            case LocalSshAuthKeyPair localAuthKeyPair -> localAuthKeyPair.username();
        };
    }

    static LocalSshAuthKeyPair getLocalAuthInstance(String username, List<PublicKey> publicKeys, String repositoryName) {
        return getLocalAuthInstance(username, publicKeys, repositoryName, null);
    }

    static LocalSshAuthKeyPair getLocalAuthInstance(String username, List<PublicKey> publicKeys, String repositoryName, String localSshPrivateKeyPath) {
        KeyPair keyPair = readConfiguredLocalSshKey(localSshPrivateKeyPath).orElseGet(Auth::generateLocalSshKeyPair);
        return new LocalSshAuthKeyPair(username, Optional.of(keyPair), publicKeys, repositoryName);
    }

    private static Optional<KeyPair> readConfiguredLocalSshKey(String privateKeyPath) {
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(KeyUtils.readKeyFromFile(Path.of(privateKeyPath))
                    .valueOrWarning("Can't read local SSH private key {}", privateKeyPath));
        } catch (InvalidPathException e) {
            Logger.log.warn("Can't read local SSH private key from invalid path {}", privateKeyPath, e);
            return Optional.empty();
        }
    }

    private static KeyPair generateLocalSshKeyPair() {
        KeyPair keyPair = KeyUtils.generateRSAKeyPair().valueOrFailure("Couldn't generate key");
        Logger.log.info("Generated keypair to access via SSH protocol: {}", keyPair.getPublic());
        return keyPair;
    }

    default boolean matchesLocalRepository(String repositoryName) {
        return switch (this) {
            case LocalNoneAuth noneAuth -> Objects.equals(noneAuth.localRepositoryName(), repositoryName);
            case LocalSshAuthKeyPair localAuthKeyPair -> Objects.equals(localAuthKeyPair.localRepositoryName(), repositoryName);
            default -> false;
        };
    }

    @Slf4j
    static class Logger {
    }
}
