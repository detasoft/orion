package pro.deta.orion.git.parser.v2.capability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitCapabilityTest {
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
    void resolvesWireNamesWithoutUsingEnumIdentifiers() {
        assertThat(GitCapability.findByWireName("multi_ack")).contains(GitCapability.MULTI_ACK);
        assertThat(GitCapability.findByWireName("side-band-64k")).contains(GitCapability.SIDE_BAND_64K);
        assertThat(GitCapability.findByWireName("agent")).contains(GitCapability.AGENT);
        assertThat(GitCapability.findByWireName("packfile-uris")).contains(GitCapability.PACKFILE_URIS);
        assertThat(GitCapability.findByWireName("MULTI_ACK")).isEmpty();
        assertThat(GitCapability.findByWireName("agent=orion")).isEmpty();
        assertThat(GitCapability.findByWireName("vendor-option")).isEmpty();
    }

}
