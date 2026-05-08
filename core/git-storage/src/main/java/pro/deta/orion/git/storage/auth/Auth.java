package pro.deta.orion.git.storage.auth;

import pro.deta.orion.util.KeyUtils;

import java.nio.file.Path;
import java.security.KeyPair;
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

    record LocalNoneAuth(String localRepositoryName) implements Auth {
    }

    default String getUsername() {
        return switch (this) {
            case HttpAuth httpAuth -> httpAuth.username();
            case LocalNoneAuth noneAuth -> "LOCAL_AUTH_EMPTY";
            case SshAuthKey sshAuthKey -> sshAuthKey.username();

            case SshAuthKeyPair sshAuthKeyPair -> sshAuthKeyPair.username();
        };
    }

    default boolean matchesLocalRepository(String repositoryName) {
        return switch (this) {
            case LocalNoneAuth noneAuth -> Objects.equals(noneAuth.localRepositoryName(), repositoryName);
            default -> false;
        };
    }
}
