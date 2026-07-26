package pro.deta.orion.git.parser.wire;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCapabilityParserWriterTest {
    private final GitCapabilityParser parser = new GitCapabilityParser();
    private final GitCapabilityWriter writer = new GitCapabilityWriter();

    @Test
    void parsesV0AdvertisementCapabilitiesAfterNul() {
        String advertisement = "1111111111111111111111111111111111111111 HEAD\0"
                + "multi_ack thin-pack object-format=sha256 agent=orion/1.0 unknown=raw=value";

        GitCapabilitySet capabilities = parser.parseAdvertisementLine(advertisement);

        assertThat(capabilities.names())
                .containsExactly("multi_ack", "thin-pack", "object-format", "agent", "unknown");
        assertThat(capabilities.value("multi_ack")).isEmpty();
        assertThat(capabilities.value("object-format")).contains("sha256");
        assertThat(capabilities.value("unknown")).contains("raw=value");
        assertThat(capabilities.asList().get(4).rawToken()).isEqualTo("unknown=raw=value");
    }

    @Test
    void returnsEmptySetWhenV0AdvertisementLineHasNoCapabilities() {
        GitCapabilitySet capabilities = parser.parseAdvertisementLine(
                "1111111111111111111111111111111111111111 refs/heads/main");

        assertThat(capabilities.asList()).isEmpty();
    }

    @Test
    void parsesProtocolV2CapabilityLinesWithOptionalTrailingLf() {
        GitCapabilitySet capabilities = parser.parseProtocolV2Lines(List.of(
                "version 2\n",
                "ls-refs=unborn\n",
                "fetch=shallow wait-for-done filter",
                "server-option"));

        assertThat(capabilities.names()).containsExactly("version 2", "ls-refs", "fetch", "server-option");
        assertThat(capabilities.value("version 2")).isEmpty();
        assertThat(capabilities.value("ls-refs")).contains("unborn");
        assertThat(capabilities.value("fetch")).contains("shallow wait-for-done filter");
        assertThat(capabilities.value("server-option")).isEmpty();
    }

    @Test
    void writesV0AdvertisementLineWithCapabilitiesAfterNul() {
        String advertisement = writer.writeAdvertisementLine(
                "1111111111111111111111111111111111111111 HEAD",
                List.of(
                        GitCapability.bare("multi_ack"),
                        GitCapability.of("object-format", "sha256"),
                        GitCapability.of("unknown", "raw=value")));

        assertThat(advertisement).isEqualTo(
                "1111111111111111111111111111111111111111 HEAD\0multi_ack object-format=sha256 unknown=raw=value");
    }

    @Test
    void writesProtocolV2CapabilityLinesWithLineEndings() {
        List<String> lines = writer.writeProtocolV2Lines(List.of(
                GitCapability.bare("version 2"),
                GitCapability.of("ls-refs", "unborn"),
                GitCapability.of("fetch", "shallow wait-for-done filter")));

        assertThat(lines).containsExactly(
                "version 2\n",
                "ls-refs=unborn\n",
                "fetch=shallow wait-for-done filter\n");
    }

    @Test
    void rejectsInvalidCapabilityName() {
        assertThatThrownBy(() -> GitCapability.of("bad name", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Capability name must not contain whitespace or '='");
    }
}
