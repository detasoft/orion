package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.GitTransport;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.storage.InMemoryGitStorage;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FetchPlanTest {
    private static final ObjectId WANT = new ObjectId("1".repeat(40));
    private static final ObjectId UNKNOWN = new ObjectId("2".repeat(40));
    private static final RefId MAIN = new RefId("refs/heads/main");
    private final InMemoryGitStorage storage = new InMemoryGitStorage();
    private final FetchCommand command = new FetchCommand(storage.api, Set.of(
            GitCapability.MULTI_ACK, GitCapability.MULTI_ACK_DETAILED,
            GitCapability.NO_DONE, GitCapability.WAIT_FOR_DONE,
            GitCapability.SHALLOW, GitCapability.FILTER, GitCapability.REF_IN_WANT,
            GitCapability.PACKFILE_URIS, GitCapability.SIDEBAND_ALL));

    @Test
    void unfinishedRoundProducesNoPlan() throws Exception {
        var iterator = command.prepareNegotiation(request(FetchRequest.Mode.PROTOCOL_V2), GitTransport.HTTP);
        iterator.next(new NegotiationMessage.Have(UNKNOWN));
        iterator.next(NegotiationMessage.Control.END_ROUND);
        assertThat(command.prepareResponse(iterator.getContext())).isEmpty();
    }

    @Test
    void doneWithoutCommonObjectsProducesAPlanInEveryMode() throws Exception {
        for (FetchRequest.Mode mode : FetchRequest.Mode.values()) {
            var iterator = command.prepareNegotiation(request(mode), GitTransport.HTTP);
            iterator.next(NegotiationMessage.Control.DONE);
            if (mode == FetchRequest.Mode.PROTOCOL_V2) {
                iterator.next(NegotiationMessage.Control.END_ROUND);
            }
            int reads = storage.lookups.size();
            var plan = command.prepareResponse(iterator.getContext()).orElseThrow();
            assertThat(plan.wantedObjects()).containsExactly(WANT);
            assertThat(plan.commonObjects()).isEmpty();
            assertThat(storage.lookups).hasSize(reads);
        }
    }

    @Test
    void v2ReadyAllowsAPlanWithoutDone() throws Exception {
        var iterator = command.prepareNegotiation(request(FetchRequest.Mode.PROTOCOL_V2), GitTransport.HTTP);
        iterator.next(new NegotiationMessage.Have(WANT));
        iterator.next(NegotiationMessage.Control.END_ROUND);
        assertThat(iterator.getContext().ready()).isTrue();
        assertThat(iterator.getContext().doneReceived()).isFalse();
        assertThat(command.prepareResponse(iterator.getContext()).orElseThrow().commonObjects())
                .containsExactly(WANT);
    }

    @Test
    void legacyReadyStillRequiresDoneUnlessNoDoneWasNegotiated() throws Exception {
        for (boolean noDone : new boolean[]{false, true}) {
            var request = request(FetchRequest.Mode.MULTI_ACK_DETAILED);
            if (noDone) {
                request.capabilities().add(GitCapability.NO_DONE.entry());
            }
            var iterator = command.prepareNegotiation(request, GitTransport.HTTP);
            iterator.next(new NegotiationMessage.Have(WANT));
            iterator.next(NegotiationMessage.Control.END_ROUND);
            assertThat(iterator.getContext().ready()).isTrue();
            assertThat(command.prepareResponse(iterator.getContext()).isPresent()).isEqualTo(noDone);
        }
    }

    @Test
    void waitForDoneKeepsThePlanAbsentUntilDone() throws Exception {
        var request = request(FetchRequest.Mode.PROTOCOL_V2);
        request.capabilities().add(GitCapability.WAIT_FOR_DONE.entry());
        var iterator = command.prepareNegotiation(request, GitTransport.HTTP);
        iterator.next(new NegotiationMessage.Have(WANT));
        iterator.next(NegotiationMessage.Control.END_ROUND);
        assertThat(command.prepareResponse(iterator.getContext())).isEmpty();

        var next = command.prepareNegotiation(request, GitTransport.HTTP);
        next.next(NegotiationMessage.Control.DONE);
        next.next(NegotiationMessage.Control.END_ROUND);
        assertThat(command.prepareResponse(next.getContext())).isPresent();
    }

    @Test
    void snapshotsResolvedRefsCommonObjectsAndPackOptions() throws Exception {
        var request = request(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add(MAIN.value());
        request.shallowCommits().add(WANT);
        request.setDepth(OptionalInt.of(5));
        request.setFilter(Optional.of("blob:none"));
        request.capabilities().addAll(Set.of(GitCapability.THIN_PACK.entry(), GitCapability.OFS_DELTA.entry(),
                GitCapability.INCLUDE_TAG.entry(), GitCapability.NO_PROGRESS.entry(),
                GitCapability.DEEPEN_RELATIVE.entry(), GitCapability.SIDEBAND_ALL.entry()));
        request.packfileUriProtocols().add("https");
        storage.refs = new RefsSnapshot(Map.of(MAIN, WANT), new Head.Symbolic(MAIN));
        var iterator = command.prepareNegotiation(request, GitTransport.HTTP);
        iterator.next(new NegotiationMessage.Have(WANT));
        iterator.next(NegotiationMessage.Control.DONE);
        iterator.next(NegotiationMessage.Control.END_ROUND);
        var plan = command.prepareResponse(iterator.getContext()).orElseThrow();

        request.wants().clear();
        request.wantRefs().clear();
        request.shallowCommits().clear();
        request.capabilities().clear();
        request.packfileUriProtocols().clear();
        request.setDepth(OptionalInt.empty());
        request.setFilter(Optional.empty());
        assertThat(plan.wantedObjects()).containsExactly(WANT);
        assertThat(plan.wantedRefs()).containsExactly(Map.entry(MAIN, WANT));
        assertThat(plan.commonObjects()).containsExactly(WANT);
        assertThat(plan.shallowCommits()).containsExactly(WANT);
        assertThat(plan.depth()).hasValue(5);
        assertThat(plan.filter()).contains("blob:none");
        assertThat(plan.capabilities()).contains(GitCapability.THIN_PACK.entry(),
                GitCapability.OFS_DELTA.entry(), GitCapability.DEEPEN_RELATIVE.entry());
        assertThat(plan.packfileUriProtocols()).containsExactly("https");
        assertThatThrownBy(() -> plan.wantedObjects().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.wantedRefs().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.commonObjects().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.shallowCommits().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.capabilities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.packfileUriProtocols().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesTimeAndRefExclusionsWithoutExpandingThem() throws Exception {
        var request = request(FetchRequest.Mode.PROTOCOL_V2);
        request.setDeepenSince(OptionalLong.of(1234));
        request.deepenNot().add("refs/heads/excluded");
        var iterator = command.prepareNegotiation(request, GitTransport.HTTP);
        iterator.next(NegotiationMessage.Control.DONE);
        iterator.next(NegotiationMessage.Control.END_ROUND);
        var plan = command.prepareResponse(iterator.getContext()).orElseThrow();
        request.deepenNot().clear();
        request.setDeepenSince(OptionalLong.empty());
        assertThat(plan.deepenSince()).hasValue(1234);
        assertThat(plan.deepenNot()).containsExactly("refs/heads/excluded");
        assertThatThrownBy(() -> plan.deepenNot().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private FetchRequest request(FetchRequest.Mode mode) {
        storage.put(WANT, ObjectType.BLOB, Optional.empty(), new byte[]{42});
        var request = new FetchRequest();
        request.setMode(mode);
        request.wants().add(WANT);
        return request;
    }
}
