package pro.deta.orion.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;

class OrionPasswordHashingServiceTest {
    private static final String PASSWORD = "root-password";
    private static final String SHA1_PASSWORD_HASH = "be1ec2bc9b9735be8fc708736e8e74a5bd46af75";

    @Test
    void sha1AlgorithmCalculatesStableHashAndComparesPassword() {
        OrionPasswordHashingService service = new OrionPasswordHashingService();

        assertThat(service.calculateHash(SHA1, PASSWORD.toCharArray())).isEqualTo(SHA1_PASSWORD_HASH);
        assertThat(service.comparePassword(SHA1, SHA1_PASSWORD_HASH, PASSWORD.getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(service.comparePassword(SHA1, SHA1_PASSWORD_HASH, "other-password".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void explicitSha1ComparisonDoesNotDependOnConfiguredDefaultAlgorithm() {
        OrionPasswordHashingService service = new OrionPasswordHashingService();

        assertThat(service.comparePassword(SHA1, SHA1_PASSWORD_HASH, PASSWORD.getBytes(StandardCharsets.UTF_8))).isTrue();
    }
}
