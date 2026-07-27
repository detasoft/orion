package pro.deta.orion.git.parser.wire.receivepack;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.capability.GitCapabilityParser;
import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReceivePackCapabilityResolverTest {
    private final ReceivePackCapabilityResolver resolver = new ReceivePackCapabilityResolver();
    private final GitCapabilityParser capabilityParser = new GitCapabilityParser();

    private static final Set<ReceivePackCapability> ALL_ADVERTISED = EnumSet.of(
            ReceivePackCapability.REPORT_STATUS,
            ReceivePackCapability.SIDE_BAND_64K,
            ReceivePackCapability.DELETE_REFS,
            ReceivePackCapability.OFS_DELTA,
            ReceivePackCapability.ATOMIC,
            ReceivePackCapability.OBJECT_FORMAT,
            ReceivePackCapability.AGENT);

    @Test
    void selectsKnownSupportedCapabilities() {
        GitCapabilitySet client = parse("report-status side-band-64k delete-refs ofs-delta atomic");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selected()).containsExactly(
                ReceivePackCapability.REPORT_STATUS,
                ReceivePackCapability.SIDE_BAND_64K,
                ReceivePackCapability.DELETE_REFS,
                ReceivePackCapability.OFS_DELTA,
                ReceivePackCapability.ATOMIC);
        assertThat(resolution.ignored()).isEmpty();
        assertThat(resolution.rejected()).isEmpty();
    }

    @Test
    void selectsSha1ObjectFormatValue() {
        GitCapabilitySet client = parse("object-format=sha1");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selected()).containsExactly(ReceivePackCapability.OBJECT_FORMAT);
    }

    @Test
    void rejectsUnsupportedObjectFormatValue() {
        GitCapabilitySet client = parse("object-format=sha256");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isFalse();
        assertThat(resolution.rejected()).containsExactly("object-format=sha256");
    }

    @Test
    void selectsAgentCapability() {
        GitCapabilitySet client = parse("agent=git/2.43.0");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selected()).containsExactly(ReceivePackCapability.AGENT);
    }

    @Test
    void rejectsCapabilityRequiringExplicitSupportWhenServerDoesNotAdvertise() {
        Set<ReceivePackCapability> serverSupported = EnumSet.noneOf(ReceivePackCapability.class);
        GitCapabilitySet client = parse("report-status");

        ReceivePackCapabilityResolution resolution = resolver.resolve(serverSupported, client);

        assertThat(resolution.accepted()).isFalse();
        assertThat(resolution.rejected()).containsExactly("report-status");
        assertThat(resolution.selected()).isEmpty();
    }

    @Test
    void ignoresValueCapableCapabilityWhenServerDoesNotAdvertise() {
        Set<ReceivePackCapability> serverSupported = EnumSet.of(ReceivePackCapability.REPORT_STATUS);
        GitCapabilitySet client = parse("report-status object-format=sha1");

        ReceivePackCapabilityResolution resolution = resolver.resolve(serverSupported, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selected()).containsExactly(ReceivePackCapability.REPORT_STATUS);
        assertThat(resolution.ignored()).containsExactly("object-format");
    }

    @Test
    void rejectsKnownCapabilityWhenServerDoesNotAdvertiseIt() {
        GitCapabilitySet client = parse("push-options");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isFalse();
        assertThat(resolution.rejected()).containsExactly("push-options");
    }

    @Test
    void selectsSupportedAndRejectsUnsupportedCapabilities() {
        GitCapabilitySet client = parse("atomic push-options unknown-cap");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isFalse();
        assertThat(resolution.selected()).containsExactly(ReceivePackCapability.ATOMIC);
        assertThat(resolution.rejected()).containsExactly("push-options", "unknown-cap");
    }

    @Test
    void ignoresQuietWhenUnsupported() {
        GitCapabilitySet client = parse("quiet");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.ignored()).containsExactly("quiet");
    }

    @Test
    void acceptsEmptyClientCapabilitySet() {
        GitCapabilitySet client = parse("");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selected()).isEmpty();
        assertThat(resolution.ignored()).isEmpty();
        assertThat(resolution.rejected()).isEmpty();
    }

    @Test
    void usesReturnsTrueForSelectedCapability() {
        GitCapabilitySet client = parse("report-status");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.uses(ReceivePackCapability.REPORT_STATUS)).isTrue();
        assertThat(resolution.uses(ReceivePackCapability.SIDE_BAND_64K)).isFalse();
    }

    private GitCapabilitySet parse(String capabilityList) {
        return capabilityParser.parseCapabilityList(capabilityList);
    }
}
