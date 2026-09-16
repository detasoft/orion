package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.InMemoryGitStorage;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.GitTransport.SSH;

class FetchCommandTest {
    private static final ObjectId FIRST = new ObjectId("1".repeat(40));
    private static final ObjectId SECOND = new ObjectId("2".repeat(40));
    private static final RefId MAIN = new RefId("refs/heads/main");

    @Test
    void preparesParsedRequestWithoutProcessingItsNegotiationMessages() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.capabilities().add(GitCapability.WAIT_FOR_DONE.entry());
        request.wants().add(new ObjectId("1".repeat(40)));
        request.initialMessages().add(new NegotiationMessage.Have(new ObjectId("2".repeat(40))));
        request.initialMessages().add(NegotiationMessage.Control.DONE);
        var storage = storage(Set.of(FIRST));
        var command = new FetchCommand(storage.api, Set.of(GitCapability.WAIT_FOR_DONE));

        var iterator = command.prepareNegotiation(request, HTTP);

        assertThat(iterator.getContext().request()).isSameAs(request);
        assertThat(iterator.getContext().commonObjects()).isEmpty();
        assertThat(iterator.getContext().doneReceived()).isFalse();
        assertThat(iterator.getContext().ready()).isFalse();
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(storage.lookups).containsExactly(FIRST);
    }

    @Test
    void rejectsUnadvertisedCapabilitiesDuringPreparation() {
        var request = new FetchRequest();
        request.capabilities().add(GitCapability.THIN_PACK.entry());
        var command = new FetchCommand(new GitStorageApi(), Set.of());
        assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                .isInstanceOf(IOException.class).hasMessageContaining("thin-pack");
    }

    @Test
    void rejectsUnadvertisedWantRefsBeforeReadingTheStorageSnapshot() {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add("refs/heads/main");
        var command = new FetchCommand(new GitStorageApi(), Set.of());
        assertThatThrownBy(() -> command.prepareNegotiation(request, HTTP))
                .isInstanceOf(IOException.class).hasMessageContaining("ref-in-want");
    }

    @Test
    void separateRequestsGetIndependentNegotiationState() throws Exception {
        var command = new FetchCommand(new GitStorageApi(), Set.of());
        var first = command.prepareNegotiation(new FetchRequest(), SSH);
        first.next(NegotiationMessage.Control.DONE);
        var second = command.prepareNegotiation(new FetchRequest(), SSH);

        assertThat(first.getContext().doneReceived()).isTrue();
        assertThat(second.getContext()).isNotSameAs(first.getContext());
        assertThat(second.getContext().doneReceived()).isFalse();
        assertThat(second.getResponsesToSend()).isEmpty();
    }

    @Test
    void defaultAccessAllowsAnExistingObjectWithoutRequiringAnAdvertisedRef() throws Exception {
        var request = new FetchRequest();
        request.wants().add(FIRST);
        var storage = storage(Set.of(FIRST));
        var command = new FetchCommand(storage.api, Set.of());
        var iterator = command.prepareNegotiation(request, SSH);
        assertThat(iterator.getContext().wantedObjects()).containsExactly(FIRST);
        assertThat(storage.lookups).containsExactly(FIRST);
        assertThat(storage.snapshots).isZero();
    }

    @Test
    void resolvesRefsOnceAndChecksEachDistinctWantedObjectOnce() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wants().add(FIRST);
        request.wantRefs().addAll(List.of(MAIN.value(), "HEAD"));
        var storage = storage(Set.of(FIRST));
        storage.refs = new RefsSnapshot(Map.of(MAIN, FIRST), new Head.Symbolic(MAIN));
        var command = new FetchCommand(storage.api, Set.of(GitCapability.REF_IN_WANT));

        var iterator = command.prepareNegotiation(request, HTTP);

        assertThat(storage.snapshots).isEqualTo(1);
        assertThat(storage.lookups).containsExactly(FIRST);
        assertThat(iterator.getContext().wantedRefs()).containsExactly(
                Map.entry(MAIN, FIRST), Map.entry(new RefId("HEAD"), FIRST));
    }

    @Test
    void missingExplicitObjectFailsBeforeNegotiation() {
        var request = new FetchRequest();
        request.wants().addAll(List.of(FIRST, SECOND));
        var storage = storage(Set.of(FIRST));
        var command = new FetchCommand(storage.api, Set.of());
        assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(SECOND.toHex());
        assertThat(storage.lookups).containsExactly(FIRST, SECOND);
    }

    @Test
    void aRefTargetMustExistEvenWhenTheRefIsPresentInTheSnapshot() {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add(MAIN.value());
        var storage = storage(Set.of());
        storage.refs = new RefsSnapshot(Map.of(MAIN, FIRST), new Head.Symbolic(MAIN));
        var command = new FetchCommand(storage.api, Set.of(GitCapability.REF_IN_WANT));
        assertThatThrownBy(() -> command.prepareNegotiation(request, HTTP))
                .isInstanceOf(IOException.class).hasMessageContaining(FIRST.toHex());
        assertThat(storage.snapshots).isEqualTo(1);
        assertThat(storage.lookups).containsExactly(FIRST);
    }

    @Test
    void accessHookCanRejectBeforeReadingRefsOrObjects() {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add(MAIN.value());
        var storage = storage(Set.of(FIRST));
        var denied = new IOException("Fetch access denied");
        var command = new FetchCommand(storage.api, Set.of(GitCapability.REF_IN_WANT)) {
            @Override
            protected void checkFetchAccess(FetchRequest received) throws IOException {
                assertThat(received).isSameAs(request);
                throw denied;
            }
        };
        assertThatThrownBy(() -> command.prepareNegotiation(request, HTTP)).isSameAs(denied);
        assertThat(storage.snapshots).isZero();
        assertThat(storage.lookups).isEmpty();
    }

    @Test
    void storageFailurePropagatesWithoutBeingReportedAsAMissingObject() {
        var request = new FetchRequest();
        request.wants().add(FIRST);
        var storage = storage(Set.of(FIRST));
        storage.failure = new IOException("Object index unavailable");
        var command = new FetchCommand(storage.api, Set.of());
        assertThatThrownBy(() -> command.prepareNegotiation(request, SSH)).isSameAs(storage.failure);
    }

    private static InMemoryGitStorage storage(Set<ObjectId> ids) {
        var storage = new InMemoryGitStorage();
        for (ObjectId id : ids) {
            storage.put(id, ObjectType.BLOB, Optional.empty(), new byte[]{42});
        }
        return storage;
    }
}
