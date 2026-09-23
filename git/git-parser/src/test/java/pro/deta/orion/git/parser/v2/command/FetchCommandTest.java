package pro.deta.orion.git.parser.v2.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.*;
import pro.deta.orion.git.parser.v2.fetch.FetchRequest;
import pro.deta.orion.git.parser.v2.fetch.FetchTestSupport;
import pro.deta.orion.git.parser.v2.fetch.NegotiationMessage;
import pro.deta.orion.git.parser.v2.fetch.NegotiationContext;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;
import static pro.deta.orion.git.parser.v2.data.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.data.GitTransport.SSH;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;

class FetchCommandTest {
    @TempDir
    static Path directory;
    private static final ObjectId FIRST = PackTestData.objectId(GitObjectType.BLOB, new byte[]{1});
    private static final ObjectId SECOND = PackTestData.objectId(GitObjectType.BLOB, new byte[]{2});
    private static final RefId MAIN = new RefId("refs/heads/main");

    @Test
    void preparesParsedRequestWithoutProcessingItsNegotiationMessages() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.capabilities().add(value(GitCapability.WAIT_FOR_DONE));
        request.wants().add(PackTestData.objectId(GitObjectType.BLOB, new byte[]{1}));
        request.initialMessages().add(new NegotiationMessage.Have(PackTestData.objectId(GitObjectType.BLOB, new byte[]{2})));
        request.initialMessages().add(NegotiationMessage.Control.DONE);
        var storage = storage(Set.of(FIRST));
        var command = new FetchCommand(storage, capabilities(GitCapability.WAIT_FOR_DONE));

        var iterator = command.prepareNegotiation(request, HTTP);

        assertThat(iterator.getContext().request()).isSameAs(request);
        assertThat(iterator.getContext().commonObjects()).isEmpty();
        assertThat(iterator.getContext().doneReceived()).isFalse();
        assertThat(iterator.getContext().ready()).isFalse();
        assertThat(iterator.getResponsesToSend()).isEmpty();
    }

    @Test
    void rejectsUnadvertisedCapabilitiesDuringPreparation() throws Exception {
        var request = new FetchRequest();
        request.capabilities().add(value(GitCapability.THIN_PACK));
        var command = new FetchCommand(FetchTestSupport.storage(directory), capabilities());
        assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                .isInstanceOf(IOException.class).hasMessageContaining("thin-pack");
    }

    @Test
    void rejectsUnadvertisedWantRefsBeforeReadingTheStorageSnapshot() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add("refs/heads/main");
        var command = new FetchCommand(FetchTestSupport.storage(directory), capabilities());
        assertThatThrownBy(() -> command.prepareNegotiation(request, HTTP))
                .isInstanceOf(IOException.class).hasMessageContaining("ref-in-want");
    }

    @Test
    void separateRequestsGetIndependentNegotiationState() throws Exception {
        var command = new FetchCommand(FetchTestSupport.storage(directory), capabilities());
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
        var command = new FetchCommand(storage, capabilities());
        var iterator = command.prepareNegotiation(request, SSH);
        assertThat(iterator.getContext().wantedObjects()).containsExactly(FIRST);
    }

    @Test
    void resolvesRefsAndDeduplicatesWantedObjects() throws Exception {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wants().add(FIRST);
        request.wantRefs().addAll(List.of(MAIN.value(), "HEAD"));
        var storage = storage(Set.of(FIRST));
        storage.updateRefs(List.of(new RefUpdate(MAIN, Optional.empty(), Optional.of(FIRST))), true);
        var command = new FetchCommand(storage, capabilities(GitCapability.REF_IN_WANT));

        var iterator = command.prepareNegotiation(request, HTTP);

        assertThat(iterator.getContext().wantedRefs()).containsExactly(
                Map.entry(MAIN, FIRST), Map.entry(new RefId("HEAD"), FIRST));
    }

    @Test
    void missingExplicitObjectFailsBeforeNegotiation() throws Exception {
        var request = new FetchRequest();
        request.wants().addAll(List.of(FIRST, SECOND));
        var storage = storage(Set.of(FIRST));
        var command = new FetchCommand(storage, capabilities());
        assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                .isInstanceOf(IOException.class).hasMessageContaining(SECOND.toHex());
    }

    @Test
    void accessHookCanRejectResolvedWantsBeforeCheckingObjectExistence() throws Exception {
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().add(MAIN.value());
        request.wants().add(SECOND);
        GitStorageApi storage = storage(Set.of(FIRST));
        storage.updateRefs(List.of(new RefUpdate(MAIN, Optional.empty(), Optional.of(FIRST))), true);
        IOException denied = new IOException("Fetch access denied");
        GitRepositoryContext repository = new GitRepositoryContext(storage) {
            @Override
            public void checkFetchAccess(NegotiationContext context, RefsSnapshot snapshot) throws IOException {
                assertThat(context.request()).isSameAs(request);
                assertThat(context.wantedObjects()).containsExactly(SECOND, FIRST);
                assertThat(context.wantedRefs()).containsExactly(Map.entry(MAIN, FIRST));
                assertThat(snapshot.refs()).containsEntry(MAIN, FIRST);
                throw denied;
            }
        };
        FetchCommand command = new FetchCommand(repository, capabilities(GitCapability.REF_IN_WANT));
        assertThatThrownBy(() -> command.prepareNegotiation(request, HTTP)).isSameAs(denied);
    }

    private static GitStorageApi storage(Set<ObjectId> ids) throws Exception {
        GitStorageApi storage = FetchTestSupport.storage(directory);
        for (ObjectId id : ids) {
            PackTestData.store(storage, GitObjectType.BLOB, new byte[]{(byte) (id.equals(FIRST) ? 1 : 2)});
        }
        return storage;
    }
}
