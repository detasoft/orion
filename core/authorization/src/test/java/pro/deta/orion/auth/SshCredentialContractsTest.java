package pro.deta.orion.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SshCredentialContractsTest {
    @Test
    void listSuccessDefensivelyCopiesCredentials() {
        List<SshCredential> credentials = new ArrayList<>();
        credentials.add(new SshCredential("ssh-ed25519", "SHA256:one"));

        SshCredentialListResult.Success result = new SshCredentialListResult.Success(credentials);
        credentials.clear();

        assertThat(result.credentials()).containsExactly(new SshCredential("ssh-ed25519", "SHA256:one"));
    }

    @Test
    void updateResultsDefensivelyCopyCollections() {
        List<SshCredential> credentials = new ArrayList<>();
        credentials.add(new SshCredential("ssh-rsa", "SHA256:one"));
        List<String> candidates = new ArrayList<>(List.of("SHA256:a", "SHA256:b"));

        SshCredentialUpdateResult.Success success = new SshCredentialUpdateResult.Success(credentials, true);
        SshCredentialUpdateResult.Failure failure = new SshCredentialUpdateResult.Failure(
                SshCredentialFailureCode.AMBIGUOUS_MATCH,
                "ambiguous",
                candidates,
                null);
        credentials.clear();
        candidates.clear();

        assertThat(success.credentials()).containsExactly(new SshCredential("ssh-rsa", "SHA256:one"));
        assertThat(failure.candidates()).containsExactly("SHA256:a", "SHA256:b");
    }

    @Test
    void successCollectionsAndCredentialFieldsRejectNull() {
        assertThatNullPointerException().isThrownBy(() -> new SshCredential(null, "SHA256:one"));
        assertThatNullPointerException().isThrownBy(() -> new SshCredential("ssh-rsa", null));
        assertThatNullPointerException().isThrownBy(() -> new SshCredentialListResult.Success(null));
        assertThatNullPointerException().isThrownBy(() -> new SshCredentialUpdateResult.Success(null, false));
    }
}
