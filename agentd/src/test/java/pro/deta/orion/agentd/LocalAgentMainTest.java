package pro.deta.orion.agentd;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAgentMainTest {
    @Test
    void defaultsUseTheLocalServerAndExistingSshAuthentication() {
        var options = LocalAgentMain.options(new String[0]);
        assertThat(options.server().toString()).isEqualTo("https://localhost:8443");
        assertThat(options.label()).isEqualTo("local");
        assertThat(options.sshCommand()).contains("ssh", "BatchMode=yes", "8022", "root", "localhost");
        assertThat(options.sshCommand().getLast()).startsWith("issue-launch-permit ");
    }

    @Test
    void preservesQuotedPathsAndSshOptionsWithoutExecutingAShell() {
        var options = LocalAgentMain.options(new String[]{
                "--state-dir", "/tmp/agent's files", "--ssh-port", "9022",
                "--ssh-option", "IdentityFile=/tmp/my key"});
        assertThat(options.sshCommand()).contains("9022", "IdentityFile=/tmp/my key");
        assertThat(options.sshCommand().getLast()).contains("\"/tmp/agent's files\"");
    }

    @Test
    void validatesOptionsBeforeRequestingAPermit() {
        for (String[] arguments : List.of(
                new String[]{"--server", "http://localhost"},
                new String[]{"--ssh-port", "0"},
                new String[]{"--ssh-port", "65536"},
                new String[]{"--generation", "1"},
                new String[]{"--state-dir"})) {
            assertThatThrownBy(() -> LocalAgentMain.options(arguments)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validatesTheCompletePermitResponseWithoutIncludingItInErrors() throws Exception {
        String permit = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String response = "3\n00000000-0000-0000-0000-000000000001\n" + permit + "\n";
        try (var launch = LocalAgentMain.authorization("local", response.getBytes(StandardCharsets.US_ASCII))) {
            assertThat(launch.generation().value()).isEqualTo(3);
            assertThat(launch.permit().copyBytes()).hasSize(32);
            assertThat(launch.toString()).doesNotContain(permit);
        }
        for (String invalid : List.of(response + "extra\n", "0\ninvalid\nsecret-value\n", "secret-value")) {
            assertThatThrownBy(() -> LocalAgentMain.authorization("local", invalid.getBytes(StandardCharsets.US_ASCII)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid launch authorization response");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
            org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void boundsSshOutputAndRejectsFailedCommandsWithoutLeakingTheirOutput() throws Exception {
        assertThat(LocalAgentMain.requestPermit(List.of("/bin/sh", "-c", "printf 'response\\n'")))
                .isEqualTo("response\n".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> LocalAgentMain.requestPermit(
                List.of("/bin/sh", "-c", "printf 'private-response'; exit 1")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageNotContaining("private-response");
        assertThatThrownBy(() -> LocalAgentMain.requestPermit(
                List.of("/bin/sh", "-c", "head -c 4097 /dev/zero")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("too large");
    }
}
