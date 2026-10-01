package pro.deta.orion.agentd.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMachineInfoTest {
    @Test
    void reportsBoundedNonBlankLocalMachineFields() {
        var machine = new LocalMachineInfo(() -> "runner-1").read();

        assertThat(machine.hostname()).isEqualTo("runner-1");
        assertThat(machine.operatingSystem()).isNotBlank();
        assertThat(machine.architecture()).isNotBlank();
        assertThat(machine.hostname().length()).isLessThanOrEqualTo(128);
    }

    @Test
    void hostnameLookupFailureUsesSafeFallback() {
        var machine = new LocalMachineInfo(() -> {
            throw new IllegalStateException("DNS unavailable");
        }).read();

        assertThat(machine.hostname()).isEqualTo("unknown-host");
    }
}
