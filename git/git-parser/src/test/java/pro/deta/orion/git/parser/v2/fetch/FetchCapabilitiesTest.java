package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.GitTransport;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.capability.GitCapability;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;

import static pro.deta.orion.git.parser.v2.data.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.data.GitTransport.SSH;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.capability.GitCapability.*;

class FetchCapabilitiesTest {
    private static final ObjectId ID = new ObjectId("1".repeat(40));

    @Test
    void legacyAcceptsOnlyAdvertisedFlags() {
        for (GitCapability capability : List.of(MULTI_ACK, MULTI_ACK_DETAILED, THIN_PACK,
                OFS_DELTA, INCLUDE_TAG, NO_PROGRESS, SIDE_BAND, SIDE_BAND_64K, SHALLOW)) {
            var request = request(false);
            request.capabilities().add(value(capability));
            assertThatCode(() -> validate(request, capabilities(capability), SSH)).doesNotThrowAnyException();
            assertThatThrownBy(() -> validate(request, capabilities(), SSH))
                    .isInstanceOf(IOException.class).hasMessageContaining(capability.wireName());
        }
    }

    @Test
    void v2BaseArgumentsDoNotNeedIndividualAdvertisements() throws Exception {
        var request = request(true);
        request.capabilities().addAll(List.of(value(THIN_PACK), value(OFS_DELTA),
                value(INCLUDE_TAG), value(NO_PROGRESS)));
        request.initialMessages().add(new NegotiationMessage.Have(ID));
        request.initialMessages().add(NegotiationMessage.Control.DONE);
        validate(request, capabilities(), HTTP);
        assertThat(request.wants()).containsExactly(ID);
    }

    @Test
    void v2ExtensionsRequireTheirAdvertisedFetchFeature() {
        for (GitCapability feature : List.of(FILTER, REF_IN_WANT, PACKFILE_URIS, SIDEBAND_ALL, WAIT_FOR_DONE)) {
            var request = request(true);
            switch (feature) {
                case FILTER -> request.setFilter(Optional.of("blob:none"));
                case REF_IN_WANT -> request.wantRefs().add("refs/heads/main");
                case PACKFILE_URIS -> request.packfileUriProtocols().add("https");
                default -> request.capabilities().add(value(feature));
            }
            assertThatThrownBy(() -> validate(request, capabilities(), HTTP))
                    .isInstanceOf(IOException.class).hasMessageContaining(feature.wireName());
            assertThatCode(() -> validate(request, capabilities(feature), HTTP)).doesNotThrowAnyException();
        }
    }

    @Test
    void everyV2ShallowArgumentIsCoveredByTheShallowFeature() {
        List<Consumer<FetchRequest>> arguments = List.of(
                request -> request.shallowCommits().add(ID),
                request -> request.setDepth(OptionalInt.of(5)),
                request -> request.setDeepenSince(OptionalLong.of(123)),
                request -> request.deepenNot().add("refs/heads/old"),
                request -> {
                    request.setDepth(OptionalInt.of(5));
                    request.capabilities().add(value(DEEPEN_RELATIVE));
                });
        for (Consumer<FetchRequest> argument : arguments) {
            var request = request(true);
            argument.accept(request);
            assertThatThrownBy(() -> validate(request, capabilities(), HTTP))
                    .isInstanceOf(IOException.class).hasMessageContaining(SHALLOW.wireName());
            assertThatCode(() -> validate(request, capabilities(SHALLOW), HTTP)).doesNotThrowAnyException();
        }
    }

    @Test
    void legacyDeepeningRequiresTheCorrespondingAdvertisements() {
        var request = request(false);
        request.setDeepenSince(OptionalLong.of(123));
        request.deepenNot().add("refs/heads/old");
        assertThatThrownBy(() -> validate(request, capabilities(SHALLOW), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(DEEPEN_SINCE.wireName());
        assertThatThrownBy(() -> validate(request, capabilities(SHALLOW, DEEPEN_SINCE), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(DEEPEN_NOT.wireName());
        assertThatCode(() -> validate(request, capabilities(SHALLOW, DEEPEN_SINCE, DEEPEN_NOT), SSH))
                .doesNotThrowAnyException();
    }

    @Test
    void legacyShallowAndRelativeDepthRequireTheAdvertisedFeatures() {
        var request = request(false);
        request.shallowCommits().add(ID);
        assertThatThrownBy(() -> validate(request, capabilities(), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(SHALLOW.wireName());
        assertThatCode(() -> validate(request, capabilities(SHALLOW), SSH)).doesNotThrowAnyException();
        request.setDepth(OptionalInt.of(3));
        request.capabilities().add(value(DEEPEN_RELATIVE));
        assertThatThrownBy(() -> validate(request, capabilities(SHALLOW), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(DEEPEN_RELATIVE.wireName());
        assertThatCode(() -> validate(request, capabilities(SHALLOW, DEEPEN_RELATIVE), SSH))
                .doesNotThrowAnyException();
    }

    @Test
    void legacyFilterArgumentCannotBypassAdvertisement() {
        var request = request(false);
        request.setFilter(Optional.of("blob:none"));
        assertThatThrownBy(() -> validate(request, capabilities(), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(FILTER.wireName());
        assertThatCode(() -> validate(request, capabilities(FILTER), SSH)).doesNotThrowAnyException();
    }

    @Test
    void noDoneRequiresAdvertisementDetailedAckAndHttpTransport() {
        var request = request(false);
        request.capabilities().add(value(NO_DONE));
        GitCapabilities advertised = capabilities(NO_DONE, MULTI_ACK_DETAILED);
        assertThatThrownBy(() -> validate(request, advertised, HTTP)).isInstanceOf(IOException.class);
        request.setMode(FetchRequest.Mode.MULTI_ACK_DETAILED);
        request.capabilities().add(value(MULTI_ACK_DETAILED));
        assertThatThrownBy(() -> validate(request, advertised, SSH)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validate(request, capabilities(MULTI_ACK_DETAILED), HTTP))
                .isInstanceOf(IOException.class).hasMessageContaining(NO_DONE.wireName());
        assertThatCode(() -> validate(request, advertised, HTTP)).doesNotThrowAnyException();
    }

    @Test
    void legacySidebandModesAreMutuallyExclusiveEvenWhenBothAreAdvertised() {
        var request = request(false);
        request.capabilities().addAll(List.of(value(SIDE_BAND), value(SIDE_BAND_64K)));
        assertThatThrownBy(() -> validate(request, capabilities(SIDE_BAND, SIDE_BAND_64K), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining("side-band");
    }

    @Test
    void rejectsCapabilitiesFromAnotherCommandOrProtocolAndUnknownExtensions() {
        for (boolean v2 : List.of(false, true)) {
            for (GitCapability capability : List.of(REPORT_STATUS, ATOMIC, SYMREF,
                    v2 ? MULTI_ACK : SIDEBAND_ALL)) {
                var request = request(v2);
                request.capabilities().add(value(capability));
                assertThatThrownBy(() -> validate(request, capabilities(capability), HTTP))
                        .isInstanceOf(IOException.class).hasMessageContaining(capability.wireName());
            }
            var request = request(v2);
            request.capabilities().add(value("unknown-feature"));
            assertThatThrownBy(() -> validate(request, capabilities(), HTTP))
                    .isInstanceOf(IOException.class).hasMessageContaining("unknown-feature");
        }
    }

    @Test
    void informationalValuesRequireAdvertisementAndFlagsCannotHaveValues() {
        var request = request(false);
        request.capabilities().add(value(AGENT, "client/2"));
        request.capabilities().add(value(SESSION_ID, "client-session"));
        assertThatCode(() -> validate(request, capabilities(AGENT, SESSION_ID), SSH)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(request, capabilities(SESSION_ID), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(AGENT.wireName());
        request.capabilities().clear();
        request.capabilities().add(value(MULTI_ACK, "custom"));
        assertThatThrownBy(() -> validate(request, capabilities(MULTI_ACK), SSH)).isInstanceOf(IOException.class);
    }

    @Test
    void objectFormatUsesTheExistingSha1Contract() {
        var request = request(false);
        request.capabilities().add(value(OBJECT_FORMAT, "sha1"));
        assertThatCode(() -> validate(request, capabilities(OBJECT_FORMAT), SSH)).doesNotThrowAnyException();
        request.capabilities().clear();
        request.capabilities().add(value(OBJECT_FORMAT, "sha256"));
        assertThatThrownBy(() -> validate(request, capabilities(OBJECT_FORMAT), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining("sha1");
    }

    @Test
    void advertisementIsSnapshottedAndFailurePrecedesObjectLookup() throws Exception {
        var advertised = capabilities(FILTER);
        var request = request(true);
        request.setFilter(Optional.of("blob:none"));
        var context = new NegotiationContext(request, new GitStorageApi(), advertised);
        advertised.clear();
        new FetchNegotiatorIterator(context, HTTP);

        var denied = new NegotiationContext(request, new GitStorageApi(), advertised) {
            @Override
            public boolean objectExists(ObjectId id) {
                throw new AssertionError("Unsupported request must not query storage");
            }
        };
        advertised.add(value(FILTER));
        assertThatThrownBy(() -> {
            var iterator = new FetchNegotiatorIterator(denied, HTTP);
            iterator.next(new NegotiationMessage.Have(ID));
        }).isInstanceOf(IOException.class).hasMessageContaining(FILTER.wireName());
        assertThat(denied.commonObjects()).isEmpty();
    }

    private static FetchRequest request(boolean v2) {
        var request = new FetchRequest();
        request.setMode(v2 ? FetchRequest.Mode.PROTOCOL_V2 : FetchRequest.Mode.SINGLE_ACK);
        request.wants().add(ID);
        return request;
    }

    private static void validate(FetchRequest request, GitCapabilities advertised, GitTransport transport)
            throws IOException {
        new FetchNegotiatorIterator(new NegotiationContext(request, new GitStorageApi(), advertised), transport);
    }
}
