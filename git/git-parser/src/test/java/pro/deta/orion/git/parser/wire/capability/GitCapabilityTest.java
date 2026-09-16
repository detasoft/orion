package pro.deta.orion.git.parser.wire.capability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitCapabilityTest {

    @Test
    void parsesKnownAndCustomTokensWithoutLosingValues() throws Exception {
        assertThat(GitCapability.parse("thin-pack", GitObjectFormat.SHA1)).isEqualTo(GitCapability.THIN_PACK.entry());
        assertThat(GitCapability.parse("agent=client/1", GitObjectFormat.SHA1)).isEqualTo(GitCapability.AGENT.withValue("client/1"));
        assertThat(GitCapability.parse("vendor-option", GitObjectFormat.SHA1)).isEqualTo(GitCapability.Entry.custom("vendor-option"));
        assertThat(GitCapability.parse("vendor-option=a=b", GitObjectFormat.SHA1))
                .isEqualTo(GitCapability.Entry.custom("vendor-option", "a=b"));
        assertThat(GitCapability.parse("object-format=sha256", GitObjectFormat.SHA256))
                .isEqualTo(GitCapability.OBJECT_FORMAT.withValue("sha256"));
    }

    @Test
    void rejectsMalformedWireTokens() {
        for (String token : new String[]{"", "=value", "agent=", "two words", "agent=two words", "agent=x\n"}) {
            assertThatThrownBy(() -> GitCapability.parse(token, GitObjectFormat.SHA1))
                    .isInstanceOf(IOException.class).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void validatesTheExpectedObjectFormat() throws Exception {
        assertThat(GitCapability.parse("object-format=sha1", GitObjectFormat.SHA1))
                .isEqualTo(GitCapability.OBJECT_FORMAT.withValue("sha1"));
        assertThatThrownBy(() -> GitCapability.parse("object-format=sha256", GitObjectFormat.SHA1))
                .isInstanceOf(IOException.class).hasMessageContaining("Expected object format sha1");
        assertThatThrownBy(() -> GitCapability.parse("object-format=sha1", GitObjectFormat.SHA256))
                .isInstanceOf(IOException.class).hasMessageContaining("Expected object format sha256");
    }

    @Test
    void exposesAllStandardProtocolV0V1Capabilities() {
        assertThat(List.of(
                GitCapability.MULTI_ACK.entry(),
                GitCapability.MULTI_ACK_DETAILED.entry(),
                GitCapability.NO_DONE.entry(),
                GitCapability.THIN_PACK.entry(),
                GitCapability.NO_THIN.entry(),
                GitCapability.SIDE_BAND.entry(),
                GitCapability.SIDE_BAND_64K.entry(),
                GitCapability.OFS_DELTA.entry(),
                GitCapability.AGENT.withValue("orion-native"),
                GitCapability.OBJECT_FORMAT.withValue("sha1"),
                GitCapability.SYMREF.withValue("HEAD:refs/heads/main"),
                GitCapability.SHALLOW.entry(),
                GitCapability.DEEPEN_SINCE.entry(),
                GitCapability.DEEPEN_NOT.entry(),
                GitCapability.DEEPEN_RELATIVE.entry(),
                GitCapability.NO_PROGRESS.entry(),
                GitCapability.INCLUDE_TAG.entry(),
                GitCapability.REPORT_STATUS.entry(),
                GitCapability.REPORT_STATUS_V2.entry(),
                GitCapability.DELETE_REFS.entry(),
                GitCapability.QUIET.entry(),
                GitCapability.ATOMIC.entry(),
                GitCapability.PUSH_OPTIONS.entry(),
                GitCapability.ALLOW_TIP_SHA1_IN_WANT.entry(),
                GitCapability.ALLOW_REACHABLE_SHA1_IN_WANT.entry(),
                GitCapability.PUSH_CERT.withValue("nonce"),
                GitCapability.FILTER.entry(),
                GitCapability.REF_IN_WANT.entry(),
                GitCapability.SESSION_ID.withValue("session")))
                .extracting(GitCapability.Entry::wireToken)
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
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapability.Entry.custom("wait-for-done"));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapability.Entry.custom("sideband-all"));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapability.Entry.custom("packfile-uris"));
    }

    @Test
    void supportsCustomBareAndValuedCapabilities() {
        assertThat(List.of(
                GitCapability.Entry.custom("bundle-uri"),
                GitCapability.Entry.custom("vendor-option", "enabled")))
                .extracting(GitCapability.Entry::wireToken)
                .containsExactly(
                        "bundle-uri",
                        "vendor-option=enabled");
    }

    @Test
    void resolvesWireNamesWithoutUsingEnumIdentifiers() {
        assertThat(GitCapability.fromWireName("multi_ack")).contains(GitCapability.MULTI_ACK);
        assertThat(GitCapability.fromWireName("side-band-64k")).contains(GitCapability.SIDE_BAND_64K);
        assertThat(GitCapability.fromWireName("agent")).contains(GitCapability.AGENT);
        assertThat(GitCapability.fromWireName("packfile-uris")).contains(GitCapability.PACKFILE_URIS);
        assertThat(GitCapability.fromWireName("MULTI_ACK")).isEmpty();
        assertThat(GitCapability.fromWireName("agent=orion")).isEmpty();
        assertThat(GitCapability.fromWireName("vendor-option")).isEmpty();
    }

    @Test
    void valuesRemainIndependentAndCannotInjectWireWhitespace() {
        GitCapability.Entry first = GitCapability.AGENT.withValue("first");
        GitCapability.Entry second = GitCapability.AGENT.withValue("second");
        assertThat(first.wireToken()).isEqualTo("agent=first");
        assertThat(second.wireToken()).isEqualTo("agent=second");
        assertThat(GitCapability.AGENT.wireName()).isEqualTo("agent");
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapability.AGENT.withValue("two words"));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapability.SESSION_ID.withValue(""));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapability.Entry.custom("vendor option"));
        assertThatIllegalArgumentException().isThrownBy(() -> GitCapability.Entry.custom("agent", "value"));
    }

    @Test
    void standardCapabilityCannotBeCreatedAsCustom() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCapability.Entry.custom("multi_ack"));
    }
}
