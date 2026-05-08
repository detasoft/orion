package pro.deta.orion.git.storage.auth;

import org.junit.jupiter.api.Test;
import pro.deta.orion.util.KeyUtils;

import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTest {
    @Test
    void matchesOnlyLocalRepositoryAuth() {
        assertThat(new Auth.LocalNoneAuth("local.git").matchesLocalRepository("local.git")).isTrue();
        assertThat(new Auth.LocalNoneAuth("local.git").matchesLocalRepository("other.git")).isFalse();
        assertThat(new Auth.HttpAuth("user", "password").matchesLocalRepository("local.git")).isFalse();
    }

    @Test
    void sshAuthUsesConfiguredKeyFile() throws Exception {
        Path keyPath = Path.of(Objects.requireNonNull(
                getClass().getResource("/pro/deta/orion/git/storage/auth/ssh-storage-rsa.pem")
        ).toURI());
        var expectedKeyPair = KeyUtils.readKeyFromFile(keyPath).valueOrFailure("Cannot read test key");

        Auth.SshAuthKey auth = new Auth.SshAuthKey("user", keyPath);

        assertThat(auth.keyPair()).isPresent();
        assertThat(auth.keyPair().get().getPrivate().getEncoded()).isEqualTo(expectedKeyPair.getPrivate().getEncoded());
        assertThat(auth.keyPair().get().getPublic().getEncoded()).isEqualTo(expectedKeyPair.getPublic().getEncoded());
    }
}
