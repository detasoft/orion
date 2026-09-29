package pro.deta.orion.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenResultsTest {
    @Test
    void issuedTokenIsAvailableToCallersAndOmittedFromDiagnostics() {
        TokenIssueResult.Success result = (TokenIssueResult.Success) TokenIssueResult.success("issue-secret", 1_100);

        assertThat(result.token()).isEqualTo("issue-secret");
        assertThat(result.expiresAtEpochSecond()).isEqualTo(1_100);
        assertThat(result.toString()).contains("1100").doesNotContain("issue-secret");
    }

    @Test
    void refreshedTokenIsAvailableToCallersAndOmittedFromDiagnostics() {
        TokenRefreshResult.Success result = (TokenRefreshResult.Success) TokenRefreshResult.success(
                "refresh-secret", 1_200);

        assertThat(result.token()).isEqualTo("refresh-secret");
        assertThat(result.expiresAtEpochSecond()).isEqualTo(1_200);
        assertThat(result.toString()).contains("1200").doesNotContain("refresh-secret");
    }
}
