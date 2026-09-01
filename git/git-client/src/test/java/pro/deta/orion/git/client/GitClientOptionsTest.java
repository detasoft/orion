package pro.deta.orion.git.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitClientOptionsTest {
    @Test
    void rejectsTimeoutsShorterThanOneMillisecond() {
        assertThatThrownBy(() -> new GitClientOptions(
                Duration.ofNanos(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one millisecond");
    }
}
