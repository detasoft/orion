package pro.deta.orion.internal.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
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
}
