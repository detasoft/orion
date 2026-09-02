package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class LaunchPermitReaderTest {
    private final LaunchPermitReader reader = new LaunchPermitReader();

    @Test
    void readsOneBoundedBase64UrlLineAndClearsOnClose() throws Exception {
        byte[] secret = new byte[32];
        secret[0] = 42;
        byte[] input = (Base64.getUrlEncoder().withoutPadding().encodeToString(secret) + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        LaunchPermit permit = reader.read(new ByteArrayInputStream(input));

        assertThat(permit.copyBytes()).containsExactly(secret);
        assertThat(permit.toString()).isEqualTo("LaunchPermit[REDACTED]");

        permit.close();
        assertThat(permit.copyBytes()).containsOnly(0);
    }

    @Test
    void rejectsEmptyMalformedMultipleAndOutOfBoundsInputWithoutEchoingIt() {
        assertRejected("");
        assertRejected("not+a+base64url+secret\n");
        assertRejected(encoded(31));
        assertRejected(encoded(513));
        assertRejected(encoded(32) + encoded(32));
        assertRejected("a".repeat(700) + "\n");
    }

    private void assertRejected(String value) {
        var assertion = assertThatIOException()
                .isThrownBy(() -> reader.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII))));
        if (!value.strip().isEmpty()) {
            assertion.withMessageNotContaining(value.strip());
        }
    }

    private static String encoded(int size) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[size]) + "\n";
    }
}
