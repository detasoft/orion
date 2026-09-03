package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PosixShellTest {
    @Test
    void quotesSpacesAndApostrophes() {
        assertThat(PosixShell.quote("/tmp/Orion's agent"))
                .isEqualTo("'/tmp/Orion'\\''s agent'");
    }

    @Test
    void rejectsCommandSeparatingControlCharacters() {
        assertThatThrownBy(() -> PosixShell.quote("value\nnext"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PosixShell.quote("value\0next"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
