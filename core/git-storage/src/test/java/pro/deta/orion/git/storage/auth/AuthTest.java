package pro.deta.orion.git.storage.auth;

import org.junit.jupiter.api.Test;
import pro.deta.orion.util.KeyUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTest {
    @Test
    void matchesOnlyLocalRepositoryAuth() {
        assertThat(new Auth.LocalNoneAuth("local.git").matchesLocalRepository("local.git")).isTrue();
        assertThat(new Auth.LocalSshAuthKeyPair("user", Optional.empty(), List.of(), "ssh.git").matchesLocalRepository("ssh.git")).isTrue();
        assertThat(new Auth.LocalNoneAuth("local.git").matchesLocalRepository("other.git")).isFalse();
        assertThat(new Auth.HttpAuth("user", "password").matchesLocalRepository("local.git")).isFalse();
    }

    @Test
    void localSshAuthUsesConfiguredKeyFile() throws Exception {
        Path keyPath = Path.of(Objects.requireNonNull(
                getClass().getResource("/pro/deta/orion/git/storage/auth/local-acl-rsa.pem")
        ).toURI());
        var expectedKeyPair = KeyUtils.readKeyFromFile(keyPath).valueOrFailure("Cannot read test key");

        Auth.LocalSshAuthKeyPair auth = Auth.getLocalAuthInstance("user", List.of(), "ssh.git", keyPath.toString());

        assertThat(auth.keyPair()).isPresent();
        assertThat(auth.keyPair().get().getPrivate().getEncoded()).isEqualTo(expectedKeyPair.getPrivate().getEncoded());
        assertThat(auth.keyPair().get().getPublic().getEncoded()).isEqualTo(expectedKeyPair.getPublic().getEncoded());
    }
}
