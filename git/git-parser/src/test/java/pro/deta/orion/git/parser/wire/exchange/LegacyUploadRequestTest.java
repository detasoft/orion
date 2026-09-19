package pro.deta.orion.git.parser.wire.exchange;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;

class LegacyUploadRequestTest {
    private static final String ID = "1".repeat(40);

    @Test
    void requiresBothTheRequestedFlagAndServerAdvertisement() {
        GitCapabilities requested = capabilities(value(GitCapability.THIN_PACK), value(GitCapability.OFS_DELTA));
        GitV1Advertisement advertised = advertisement(capabilities(value(GitCapability.THIN_PACK),
                value(GitCapability.INCLUDE_TAG)));
        LegacyUploadRequest request = request(requested, advertised);
        assertThat(request.negotiated(GitCapability.THIN_PACK)).isTrue();
        assertThat(request.negotiated(GitCapability.OFS_DELTA)).isFalse();
        assertThat(request.negotiated(GitCapability.INCLUDE_TAG)).isFalse();
        LegacyUploadRequest valued = request(capabilities(value(GitCapability.THIN_PACK, "unexpected")), advertised);
        assertThat(valued.negotiated(GitCapability.THIN_PACK)).isFalse();
    }

    @Test
    void retainsOrderedValuesAndProtectsRequestAndAdvertisementFromMutation() {
        GitCapabilities requested = capabilities(value(GitCapability.THIN_PACK),
                value(GitCapability.AGENT, "client"), value("vendor-option", "one"),
                value("vendor-option", "two"));
        GitCapabilities server = capabilities(value(GitCapability.THIN_PACK),
                value(GitCapability.AGENT, "server"));
        GitV1Advertisement advertised = advertisement(server);
        LegacyUploadRequest request = request(requested, advertised);
        requested.clear();
        server.clear();
        request.capabilities().clear();
        advertised.capabilities().clear();
        assertThat(request.negotiated(GitCapability.THIN_PACK)).isTrue();
        assertThat(request.capabilities()).extracting(GitCapabilityValue::wireToken)
                .containsExactly("thin-pack", "agent=client", "vendor-option=one", "vendor-option=two");
        assertThat(advertised.capabilities()).extracting(GitCapabilityValue::wireToken)
                .containsExactly("thin-pack", "agent=server");
    }

    private static LegacyUploadRequest request(GitCapabilities capabilities, GitV1Advertisement advertised) {
        InitialRequestData initial = new InitialRequestData(InitialRequestService.UPLOAD_PACK,
                "repository", "localhost", Map.of());
        return new LegacyUploadRequest(initial, Set.of(GitObjectId.of(ID)), capabilities, advertised);
    }

    private static GitV1Advertisement advertisement(GitCapabilities capabilities) {
        return new GitV1Advertisement(capabilities, List.of(GitAdvertisedRef.direct(ID, "refs/heads/main")));
    }

    private static GitCapabilities capabilities(GitCapabilityValue... values) {
        return new GitCapabilities(List.of(values));
    }
}
