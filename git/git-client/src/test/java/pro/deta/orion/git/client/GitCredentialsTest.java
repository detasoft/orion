package pro.deta.orion.git.client;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.orion.GitCredentialKind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCredentialsTest {
    @Test
    void ownsItsSecretAndReturnsIndependentCopies() {
        char[] source = "password".toCharArray();
        try (GitCredentials credentials = new GitCredentials(GitCredentialKind.PASSWORD, "user", source)) {
            source[0] = '!';
            char[] copy = credentials.copyCharacters();
            assertThat(copy).isEqualTo("password".toCharArray());
            copy[0] = '?';
            assertThat(credentials.copyCharacters()).isEqualTo("password".toCharArray());
            assertThat(credentials.kind()).isEqualTo(GitCredentialKind.PASSWORD);
            assertThat(credentials.username()).isEqualTo("user");
            assertThat(credentials.toString()).doesNotContain("password");
        }
    }

    @Test
    void refusesSecretAccessAfterClose() {
        GitCredentials credentials = new GitCredentials(GitCredentialKind.PASSWORD, "", "secret".toCharArray());
        credentials.close();
        credentials.close();

        assertThatThrownBy(credentials::copyCharacters)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void allowsAnEmptyPassword() {
        try (GitCredentials credentials = new GitCredentials(GitCredentialKind.PASSWORD, "user", new char[0])) {
            assertThat(credentials.copyCharacters()).isEmpty();
        }
    }

    @Test
    void rejectsMissingOrUnexpectedSecret() {
        assertThatThrownBy(() -> new GitCredentials(GitCredentialKind.TOKEN, "", new char[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitCredentials(GitCredentialKind.PRIVATE_KEY, "", new char[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitCredentials(GitCredentialKind.NONE, "", "secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
