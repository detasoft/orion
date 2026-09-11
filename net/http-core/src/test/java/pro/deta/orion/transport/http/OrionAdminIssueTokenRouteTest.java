package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminIssueTokenRouteTest {
    @Test
    void tokenResponseSerializesTokenWithoutExposingItInDiagnostics() throws Exception {
        OrionAdminIssueTokenRoute.AdminTokenResponse response =
                new OrionAdminIssueTokenRoute.AdminTokenResponse("http-token-secret", "Bearer", 900, 1_100);

        assertThat(new ObjectMapper().writeValueAsString(response))
                .contains("\"token\":\"http-token-secret\"");
        assertThat(response.toString()).doesNotContain("http-token-secret");
    }
}
