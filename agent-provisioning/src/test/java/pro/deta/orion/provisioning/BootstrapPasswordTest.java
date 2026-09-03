package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapPasswordTest {
    @Test
    void copiesCharsToDirectStorageClearsCallerAndWipesAfterUse() throws Exception {
        char[] caller = "café-secret".toCharArray();
        BootstrapPassword password = BootstrapPassword.copyAndClear(caller);

        assertThat(caller).containsOnly('\0');
        assertThat(password.isDirect()).isTrue();
        assertThat(password.isCleared()).isFalse();
        boolean matched = password.useOnce(value -> value.equals("café-secret"));
        assertThat(matched).isTrue();
        assertThat(password.isCleared()).isTrue();
        assertThat(password.toString()).doesNotContain("café-secret");
        assertThatThrownBy(() -> password.useOnce(value -> value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void clearsOwnedUtf8BytesWhenConsumerFails() {
        byte[] caller = "byte-secret".getBytes(StandardCharsets.UTF_8);
        BootstrapPassword password = BootstrapPassword.copyAndClear(caller);

        assertThat(caller).containsOnly((byte) 0);
        assertThatThrownBy(() -> password.useOnce(value -> {
            throw new Exception("consumer failed");
        })).hasMessage("consumer failed");
        assertThat(password.isCleared()).isTrue();
    }

    @Test
    void clearsEveryOwnedMutableDecodeTemporary() throws Exception {
        BootstrapPassword password = BootstrapPassword.copyAndClear("decode-secret".toCharArray());
        AtomicBoolean cleared = new AtomicBoolean();

        password.useOnce(value -> value.length(), cleared::set);

        assertThat(cleared).isTrue();
    }

    @Test
    void explicitCloseIsIdempotentAndClearsDirectStorage() {
        byte[] caller = "close-secret".getBytes(StandardCharsets.UTF_8);
        BootstrapPassword password = BootstrapPassword.copyAndClear(caller);

        password.close();
        password.close();

        assertThat(password.isDirect()).isTrue();
        assertThat(password.isCleared()).isTrue();
    }

    @Test
    void rejectsMalformedUtf8AndStillClearsCaller() {
        byte[] caller = {(byte) 0xc3, (byte) 0x28};

        assertThatThrownBy(() -> BootstrapPassword.copyAndClear(caller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(Arrays.toString(caller));
        assertThat(caller).containsOnly((byte) 0);
    }
}
