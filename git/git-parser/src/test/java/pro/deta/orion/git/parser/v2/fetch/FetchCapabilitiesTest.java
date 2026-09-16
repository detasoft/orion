package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.GitTransport;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

import static pro.deta.orion.git.parser.v2.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.GitTransport.SSH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.wire.capability.GitCapability.*;

class FetchCapabilitiesTest {
    private static final ObjectId ID = new ObjectId("1".repeat(40));

    @Test
    void legacyAcceptsOnlyAdvertisedFlags() {
        for (GitCapability capability : List.of(MULTI_ACK, MULTI_ACK_DETAILED, THIN_PACK,
                OFS_DELTA, INCLUDE_TAG, NO_PROGRESS, SIDE_BAND, SIDE_BAND_64K, SHALLOW)) {
            var request = request(false);
            request.capabilities().add(capability.entry());
            assertThatCode(() -> validate(request, Set.of(capability), SSH)).doesNotThrowAnyException();
            assertThatThrownBy(() -> validate(request, Set.of(), SSH))
                    .isInstanceOf(IOException.class).hasMessageContaining(capability.wireName());
        }
    }

    @Test
    void v2BaseArgumentsDoNotNeedIndividualAdvertisements() throws Exception {
        var request = request(true);
        request.capabilities().addAll(List.of(THIN_PACK.entry(), OFS_DELTA.entry(),
                INCLUDE_TAG.entry(), NO_PROGRESS.entry()));
        request.initialMessages().add(new NegotiationMessage.Have(ID));
        request.initialMessages().add(NegotiationMessage.Control.DONE);
        validate(request, Set.of(), HTTP);
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
                default -> request.capabilities().add(feature.entry());
            }
            assertThatThrownBy(() -> validate(request, Set.of(), HTTP))
                    .isInstanceOf(IOException.class).hasMessageContaining(feature.wireName());
            assertThatCode(() -> validate(request, Set.of(feature), HTTP)).doesNotThrowAnyException();
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
                    request.capabilities().add(DEEPEN_RELATIVE.entry());
                });
        for (Consumer<FetchRequest> argument : arguments) {
            var request = request(true);
            argument.accept(request);
            assertThatThrownBy(() -> validate(request, Set.of(), HTTP))
                    .isInstanceOf(IOException.class).hasMessageContaining(SHALLOW.wireName());
            assertThatCode(() -> validate(request, Set.of(SHALLOW), HTTP)).doesNotThrowAnyException();
        }
    }

    @Test
    void legacyDeepeningRequiresTheCorrespondingAdvertisements() {
        var request = request(false);
        request.setDeepenSince(OptionalLong.of(123));
        request.deepenNot().add("refs/heads/old");
        assertThatThrownBy(() -> validate(request, Set.of(SHALLOW), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(DEEPEN_SINCE.wireName());
        assertThatThrownBy(() -> validate(request, Set.of(SHALLOW, DEEPEN_SINCE), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(DEEPEN_NOT.wireName());
        assertThatCode(() -> validate(request, Set.of(SHALLOW, DEEPEN_SINCE, DEEPEN_NOT), SSH))
                .doesNotThrowAnyException();
    }

    @Test
    void legacyShallowAndRelativeDepthRequireTheAdvertisedFeatures() {
        var request = request(false);
        request.shallowCommits().add(ID);
        assertThatThrownBy(() -> validate(request, Set.of(), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(SHALLOW.wireName());
        assertThatCode(() -> validate(request, Set.of(SHALLOW), SSH)).doesNotThrowAnyException();
        request.setDepth(OptionalInt.of(3));
        request.capabilities().add(DEEPEN_RELATIVE.entry());
        assertThatThrownBy(() -> validate(request, Set.of(SHALLOW), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(DEEPEN_RELATIVE.wireName());
        assertThatCode(() -> validate(request, Set.of(SHALLOW, DEEPEN_RELATIVE), SSH))
                .doesNotThrowAnyException();
    }

    @Test
    void legacyFilterArgumentCannotBypassAdvertisement() {
        var request = request(false);
        request.setFilter(Optional.of("blob:none"));
        assertThatThrownBy(() -> validate(request, Set.of(), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(FILTER.wireName());
        assertThatCode(() -> validate(request, Set.of(FILTER), SSH)).doesNotThrowAnyException();
    }

    @Test
    void noDoneRequiresAdvertisementDetailedAckAndHttpTransport() {
        var request = request(false);
        request.capabilities().add(NO_DONE.entry());
        Set<GitCapability> advertised = Set.of(NO_DONE, MULTI_ACK_DETAILED);
        assertThatThrownBy(() -> validate(request, advertised, HTTP)).isInstanceOf(IOException.class);
        request.setMode(FetchRequest.Mode.MULTI_ACK_DETAILED);
        request.capabilities().add(MULTI_ACK_DETAILED.entry());
        assertThatThrownBy(() -> validate(request, advertised, SSH)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> validate(request, Set.of(MULTI_ACK_DETAILED), HTTP))
                .isInstanceOf(IOException.class).hasMessageContaining(NO_DONE.wireName());
        assertThatCode(() -> validate(request, advertised, HTTP)).doesNotThrowAnyException();
    }

    @Test
    void legacySidebandModesAreMutuallyExclusiveEvenWhenBothAreAdvertised() {
        var request = request(false);
        request.capabilities().addAll(List.of(SIDE_BAND.entry(), SIDE_BAND_64K.entry()));
        assertThatThrownBy(() -> validate(request, Set.of(SIDE_BAND, SIDE_BAND_64K), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining("side-band");
    }

    @Test
    void rejectsCapabilitiesFromAnotherCommandOrProtocolAndUnknownExtensions() {
        for (boolean v2 : List.of(false, true)) {
            for (GitCapability capability : List.of(REPORT_STATUS, ATOMIC, SYMREF,
                    v2 ? MULTI_ACK : SIDEBAND_ALL)) {
                var request = request(v2);
                request.capabilities().add(capability.entry());
                assertThatThrownBy(() -> validate(request, Set.of(capability), HTTP))
                        .isInstanceOf(IOException.class).hasMessageContaining(capability.wireName());
            }
            var request = request(v2);
            request.capabilities().add(GitCapability.Entry.custom("unknown-feature"));
            assertThatThrownBy(() -> validate(request, Set.of(), HTTP))
                    .isInstanceOf(IOException.class).hasMessageContaining("unknown-feature");
        }
    }

    @Test
    void informationalValuesRequireAdvertisementAndFlagsCannotHaveValues() {
        var request = request(false);
        request.capabilities().add(AGENT.withValue("client/2"));
        request.capabilities().add(SESSION_ID.withValue("client-session"));
        assertThatCode(() -> validate(request, Set.of(AGENT, SESSION_ID), SSH)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(request, Set.of(SESSION_ID), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(AGENT.wireName());
        request.capabilities().clear();
        request.capabilities().add(MULTI_ACK.withValue("custom"));
        assertThatThrownBy(() -> validate(request, Set.of(MULTI_ACK), SSH)).isInstanceOf(IOException.class);
    }

    @Test
    void objectFormatUsesTheExistingSha1Contract() {
        var request = request(false);
        request.capabilities().add(OBJECT_FORMAT.withValue("sha1"));
        assertThatCode(() -> validate(request, Set.of(OBJECT_FORMAT), SSH)).doesNotThrowAnyException();
        request.capabilities().clear();
        request.capabilities().add(OBJECT_FORMAT.withValue("sha256"));
        assertThatThrownBy(() -> validate(request, Set.of(OBJECT_FORMAT), SSH))
                .isInstanceOf(IOException.class).hasMessageContaining("sha1");
    }

    @Test
    void advertisementIsSnapshottedAndFailurePrecedesObjectLookup() throws Exception {
        var advertised = EnumSet.of(FILTER);
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
        advertised.add(FILTER);
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

    private static void validate(FetchRequest request, Set<GitCapability> advertised, GitTransport transport)
            throws IOException {
        new FetchNegotiatorIterator(new NegotiationContext(request, new GitStorageApi(), advertised), transport);
    }
}
