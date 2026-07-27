package pro.deta.orion.git.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitProtocolTransportOptionsTest {
    @Test
    void servicesExposeGitCommandNames() {
        assertThat(GitProtocolService.UPLOAD_PACK.command()).isEqualTo("git-upload-pack");
        assertThat(GitProtocolService.RECEIVE_PACK.command()).isEqualTo("git-receive-pack");
    }

    @Test
    void retainsBoundedTransportSettings() {
        GitProtocolTransportOptions options = new GitProtocolTransportOptions(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                Duration.ofSeconds(4),
                Duration.ofSeconds(10),
                65_520,
                1024L * 1024L);

        assertThat(options.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(options.writeTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(options.operationTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(options.maximumPacketBytes()).isEqualTo(65_520);
        assertThat(options.maximumPackBytes()).isEqualTo(1024L * 1024L);
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatIllegalArgumentException().isThrownBy(() -> options(
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> options(
                Duration.ofSeconds(1),
                Duration.ofSeconds(-1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> options(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(2)));
        assertThatIllegalArgumentException().isThrownBy(() -> options(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ZERO));
    }

    @Test
    void rejectsInvalidSizeLimits() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GitProtocolTransportOptions(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                3,
                1));
        assertThatIllegalArgumentException().isThrownBy(() -> new GitProtocolTransportOptions(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                4,
                0));
    }

    @Test
    void rejectsOperationTimeoutShorterThanAnIndividualTimeout() {
        assertThatIllegalArgumentException().isThrownBy(() -> options(
                Duration.ofSeconds(3),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)));
    }

    private static GitProtocolTransportOptions options(
            Duration connectTimeout,
            Duration readTimeout,
            Duration writeTimeout,
            Duration operationTimeout) {
        return new GitProtocolTransportOptions(
                connectTimeout,
                readTimeout,
                writeTimeout,
                operationTimeout,
                65_520,
                1024);
    }
}
