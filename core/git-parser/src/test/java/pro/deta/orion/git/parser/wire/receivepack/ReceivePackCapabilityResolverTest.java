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
            ReceivePackCapability.OBJECT_FORMAT,
            ReceivePackCapability.AGENT);

    @Test
    void selectsKnownSupportedCapabilities() {
        GitCapabilitySet client = parse("report-status side-band-64k");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selected()).containsExactly(
                ReceivePackCapability.REPORT_STATUS,
                ReceivePackCapability.SIDE_BAND_64K);
        assertThat(resolution.ignored()).isEmpty();
        assertThat(resolution.rejected()).isEmpty();
    }

    @Test
    void selectsObjectFormatWithArbitraryClientValue() {
        GitCapabilitySet client = parse("object-format=sha1");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selected()).containsExactly(ReceivePackCapability.OBJECT_FORMAT);
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
    void rejectsUnknownCapability() {
        GitCapabilitySet client = parse("atomic");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isFalse();
        assertThat(resolution.rejected()).containsExactly("atomic");
    }

    @Test
    void rejectsMultipleUnknownCapabilities() {
        GitCapabilitySet client = parse("atomic push-options quiet");

        ReceivePackCapabilityResolution resolution = resolver.resolve(ALL_ADVERTISED, client);

        assertThat(resolution.accepted()).isFalse();
        assertThat(resolution.rejected()).containsExactly("atomic", "push-options", "quiet");
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
