package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OwnedCommandInputTest {
    @Test
    void clearsOwnedCopyWithoutChangingCallerInput() throws Exception {
        byte[] callerInput = "launch-permit".getBytes(StandardCharsets.UTF_8);
        OwnedCommandInput input = new OwnedCommandInput(callerInput);

        assertThat(input.stream().readAllBytes()).isEqualTo(callerInput);
        input.close();

        assertThat(input.isCleared()).isTrue();
        assertThat(callerInput).isEqualTo("launch-permit".getBytes(StandardCharsets.UTF_8));
    }
}
