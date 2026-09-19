package pro.deta.orion.git.parser.wire.capability;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitCapabilityTest {

    @Test
    void parsesKnownAndCustomTokensWithoutLosingValues() throws Exception {
        assertThat(GitCapabilityValue.parse("thin-pack", GitHashAlgorithm.SHA1))
                .isEqualTo(GitCapabilityValue.value(GitCapability.THIN_PACK));
        assertThat(GitCapabilityValue.parse("agent=client/1", GitHashAlgorithm.SHA1))
                .isEqualTo(GitCapabilityValue.value(GitCapability.AGENT, "client/1"));
        assertThat(GitCapabilityValue.parse("vendor-option", GitHashAlgorithm.SHA1))
                .isEqualTo(GitCapabilityValue.value("vendor-option"));
        assertThat(GitCapabilityValue.parse("vendor-option=a=b", GitHashAlgorithm.SHA1))
                .isEqualTo(GitCapabilityValue.value("vendor-option", "a=b"));
        assertThat(GitCapabilityValue.parse("object-format=sha256", GitHashAlgorithm.SHA256))
                .isEqualTo(GitCapabilityValue.value(GitCapability.OBJECT_FORMAT, "sha256"));
    }

    @Test
    void rejectsMalformedWireTokens() {
        for (String token : new String[]{"", "=value", "agent=", "two words", "agent=two words", "agent=x\n"}) {
            assertThatThrownBy(() -> GitCapabilityValue.parse(token, GitHashAlgorithm.SHA1))
                    .isInstanceOf(IOException.class).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validatesTheExpectedObjectFormat() throws Exception {
        assertThat(GitCapabilityValue.parse("object-format=sha1", GitHashAlgorithm.SHA1))
                .isEqualTo(GitCapabilityValue.value(GitCapability.OBJECT_FORMAT, "sha1"));
        assertThatThrownBy(() -> GitCapabilityValue.parse("object-format=sha256", GitHashAlgorithm.SHA1))
                .isInstanceOf(IOException.class).hasMessageContaining("Expected object format sha1");
        assertThatThrownBy(() -> GitCapabilityValue.parse("object-format=sha1", GitHashAlgorithm.SHA256))
                .isInstanceOf(IOException.class).hasMessageContaining("Expected object format sha256");
    }

    @Test
    void exposesAllStandardProtocolV0V1Capabilities() {
        assertThat(List.of(
                GitCapabilityValue.value(GitCapability.MULTI_ACK),
                GitCapabilityValue.value(GitCapability.MULTI_ACK_DETAILED),
                GitCapabilityValue.value(GitCapability.NO_DONE),
                GitCapabilityValue.value(GitCapability.THIN_PACK),
                GitCapabilityValue.value(GitCapability.NO_THIN),
                GitCapabilityValue.value(GitCapability.SIDE_BAND),
                GitCapabilityValue.value(GitCapability.SIDE_BAND_64K),
                GitCapabilityValue.value(GitCapability.OFS_DELTA),
                GitCapabilityValue.value(GitCapability.AGENT, "orion-native"),
                GitCapabilityValue.value(GitCapability.OBJECT_FORMAT, "sha1"),
                GitCapabilityValue.value(GitCapability.SYMREF, "HEAD:refs/heads/main"),
                GitCapabilityValue.value(GitCapability.SHALLOW),
                GitCapabilityValue.value(GitCapability.DEEPEN_SINCE),
                GitCapabilityValue.value(GitCapability.DEEPEN_NOT),
                GitCapabilityValue.value(GitCapability.DEEPEN_RELATIVE),
                GitCapabilityValue.value(GitCapability.NO_PROGRESS),
                GitCapabilityValue.value(GitCapability.INCLUDE_TAG),
                GitCapabilityValue.value(GitCapability.REPORT_STATUS),
                GitCapabilityValue.value(GitCapability.REPORT_STATUS_V2),
                GitCapabilityValue.value(GitCapability.DELETE_REFS),
                GitCapabilityValue.value(GitCapability.QUIET),
                GitCapabilityValue.value(GitCapability.ATOMIC),
                GitCapabilityValue.value(GitCapability.PUSH_OPTIONS),
                GitCapabilityValue.value(GitCapability.ALLOW_TIP_SHA1_IN_WANT),
                GitCapabilityValue.value(GitCapability.ALLOW_REACHABLE_SHA1_IN_WANT),
                GitCapabilityValue.value(GitCapability.PUSH_CERT, "nonce"),
                GitCapabilityValue.value(GitCapability.FILTER),
                GitCapabilityValue.value(GitCapability.REF_IN_WANT),
                GitCapabilityValue.value(GitCapability.SESSION_ID, "session")))
                .extracting(GitCapabilityValue::wireToken)
                .containsExactly(
                        "multi_ack",
                        "multi_ack_detailed",
                        "no-done",
                        "thin-pack",
                        "no-thin",
                        "side-band",
                        "side-band-64k",
                        "ofs-delta",
                        "agent=orion-native",
                        "object-format=sha1",
                        "symref=HEAD:refs/heads/main",
                        "shallow",
                        "deepen-since",
                        "deepen-not",
                        "deepen-relative",
                        "no-progress",
                        "include-tag",
                        "report-status",
                        "report-status-v2",
                        "delete-refs",
                        "quiet",
                        "atomic",
                        "push-options",
                        "allow-tip-sha1-in-want",
                        "allow-reachable-sha1-in-want",
                        "push-cert=nonce",
                        "filter",
                        "ref-in-want",
                        "session-id=session");
    }

    @Test
    void exposesProtocolV2FetchFlagsAsStandardCapabilities() {
        assertThat(GitCapability.WAIT_FOR_DONE.wireName()).isEqualTo("wait-for-done");
        assertThat(GitCapability.SIDEBAND_ALL.wireName()).isEqualTo("sideband-all");
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapabilityValue.value("wait-for-done"));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapabilityValue.value("sideband-all"));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapabilityValue.value("packfile-uris"));
    }

    @Test
    void supportsCustomBareAndValuedCapabilities() {
        assertThat(List.of(
                GitCapabilityValue.value("bundle-uri"),
                GitCapabilityValue.value("vendor-option", "enabled")))
                .extracting(GitCapabilityValue::wireToken)
                .containsExactly(
                        "bundle-uri",
                        "vendor-option=enabled");
    }

    @Test
    void resolvesWireNamesWithoutUsingEnumIdentifiers() {
        assertThat(GitCapability.findByWireName("multi_ack")).contains(GitCapability.MULTI_ACK);
        assertThat(GitCapability.findByWireName("side-band-64k")).contains(GitCapability.SIDE_BAND_64K);
        assertThat(GitCapability.findByWireName("agent")).contains(GitCapability.AGENT);
        assertThat(GitCapability.findByWireName("packfile-uris")).contains(GitCapability.PACKFILE_URIS);
        assertThat(GitCapability.findByWireName("MULTI_ACK")).isEmpty();
        assertThat(GitCapability.findByWireName("agent=orion")).isEmpty();
        assertThat(GitCapability.findByWireName("vendor-option")).isEmpty();
    }

    @Test
    void valuesRemainIndependentAndCannotInjectWireWhitespace() {
        GitCapabilityValue first = GitCapabilityValue.value(GitCapability.AGENT, "first");
        GitCapabilityValue second = GitCapabilityValue.value(GitCapability.AGENT, "second");
        assertThat(first.wireToken()).isEqualTo("agent=first");
        assertThat(second.wireToken()).isEqualTo("agent=second");
        assertThat(GitCapability.AGENT.wireName()).isEqualTo("agent");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCapabilityValue.value(GitCapability.AGENT, "two words"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCapabilityValue.value(GitCapability.SESSION_ID, ""));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapabilityValue.value("vendor option"));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapabilityValue.value("agent", "value"));
    }

    @Test
    void standardCapabilityCannotBeCreatedAsCustom() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCapabilityValue.value("multi_ack"));
    }
}
