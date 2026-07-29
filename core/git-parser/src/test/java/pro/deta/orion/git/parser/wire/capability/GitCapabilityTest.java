package pro.deta.orion.git.parser.wire.capability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitCapabilityTest {

    @Test
    void exposesAllStandardProtocolV0V1Capabilities() {
        assertThat(List.of(
                GitCapability.MULTI_ACK,
                GitCapability.MULTI_ACK_DETAILED,
                GitCapability.NO_DONE,
                GitCapability.THIN_PACK,
                GitCapability.NO_THIN,
                GitCapability.SIDE_BAND,
                GitCapability.SIDE_BAND_64K,
                GitCapability.OFS_DELTA,
                GitCapability.agent("orion-native"),
                GitCapability.objectFormat("sha1"),
                GitCapability.symref("HEAD", "refs/heads/main"),
                GitCapability.SHALLOW,
                GitCapability.DEEPEN_SINCE,
                GitCapability.DEEPEN_NOT,
                GitCapability.DEEPEN_RELATIVE,
                GitCapability.NO_PROGRESS,
                GitCapability.INCLUDE_TAG,
                GitCapability.REPORT_STATUS,
                GitCapability.REPORT_STATUS_V2,
                GitCapability.DELETE_REFS,
                GitCapability.QUIET,
                GitCapability.ATOMIC,
                GitCapability.PUSH_OPTIONS,
                GitCapability.ALLOW_TIP_SHA1_IN_WANT,
                GitCapability.ALLOW_REACHABLE_SHA1_IN_WANT,
                GitCapability.pushCert("nonce"),
                GitCapability.FILTER,
                GitCapability.sessionId("session")))
                .extracting(GitCapability::wireToken)
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
                        "session-id=session");
    }

    @Test
    void supportsCustomBareAndValuedCapabilities() {
        assertThat(List.of(
                GitCapability.custom("bundle-uri"),
                GitCapability.custom("vendor-option", "enabled")))
                .extracting(GitCapability::wireToken)
                .containsExactly(
                        "bundle-uri",
                        "vendor-option=enabled");
    }

    @Test
    void standardCapabilityCannotBeCreatedAsCustom() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GitCapability.custom("multi_ack"));
    }
}
